package party.qwer.iris.bridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import party.qwer.iris.CommandParser
import party.qwer.iris.Configurable
import party.qwer.iris.IrisLogger
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class H2cDispatcher internal constructor(
    transportOverride: String? = null,
    queueCapacityOverride: Int? = null,
    maxDeliveryAttemptsOverride: Int? = null,
    backoffDelayProviderOverride: (Int) -> Long = ::nextBackoffDelayMs,
) : Closeable {
    private val scopeJob = SupervisorJob()
    private val coroutineScope = CoroutineScope(scopeJob + Dispatchers.IO)
    private val routeQueueCapacity = queueCapacityOverride ?: DISPATCH_QUEUE_CAPACITY
    private val maxDeliveryAttempts = maxDeliveryAttemptsOverride ?: MAX_DELIVERY_ATTEMPTS
    private val backoffDelayProvider = backoffDelayProviderOverride

    private val webhookTransport: WebhookTransport =
        run {
            val raw =
                transportOverride?.trim()?.lowercase()
                    ?: System
                        .getenv("IRIS_WEBHOOK_TRANSPORT")
                        ?.trim()
                        ?.lowercase()
                        .orEmpty()
            when (raw) {
                "http1", "http1_1", "http", "https" -> WebhookTransport.HTTP1
                "", "h2c" -> WebhookTransport.H2C
                else -> WebhookTransport.H2C
            }
        }

    private val sharedDispatcher =
        Dispatcher().apply {
            maxRequests = MAX_CONCURRENT_REQUESTS
            maxRequestsPerHost = MAX_CONCURRENT_REQUESTS_PER_HOST
        }
    private val sharedConnectionPool =
        ConnectionPool(
            MAX_IDLE_CONNECTIONS,
            KEEP_ALIVE_DURATION_MS,
            TimeUnit.MILLISECONDS,
        )
    private val baseClient: OkHttpClient = createBaseClient()
    private val h2cClient: OkHttpClient = baseClient.newBuilder().protocols(listOf(Protocol.H2_PRIOR_KNOWLEDGE)).build()
    private val http1Client: OkHttpClient = baseClient.newBuilder().protocols(listOf(Protocol.HTTP_1_1)).build()
    private val routeDispatchers = RouteDispatchRegistry(::createRouteDispatchState)

    @Volatile
    private var started = true

    private fun createBaseClient(): OkHttpClient =
        OkHttpClient
            .Builder()
            .retryOnConnectionFailure(false)
            .dispatcher(sharedDispatcher)
            .connectionPool(sharedConnectionPool)
            .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(SOCKET_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()

    private fun clientFor(webhookUrl: String): OkHttpClient {
        // H2 prior knowledge is cleartext-only; HTTPS must use the HTTP/1.1-capable client.
        if (webhookUrl.startsWith("https://")) {
            return http1Client
        }

        return when (webhookTransport) {
            WebhookTransport.H2C -> h2cClient
            WebhookTransport.HTTP1 -> http1Client
        }
    }

    private fun createRouteDispatchState(route: String): RouteDispatchState {
        val dispatchChannel = Channel<QueuedDelivery>(capacity = routeQueueCapacity)
        val queuedMessageIds = ConcurrentHashMap.newKeySet<String>()
        val workerJob =
            coroutineScope.launch {
                for (delivery in dispatchChannel) {
                    processQueuedDelivery(delivery)
                }
            }

        return RouteDispatchState(
            route = route,
            queuedMessageIds = queuedMessageIds,
            dispatchChannel = dispatchChannel,
            workerJob = workerJob,
        )
    }

    fun route(command: RoutingCommand): RoutingResult {
        if (!started) {
            return RoutingResult.RETRY_LATER
        }

        val parsedCommand = CommandParser.parse(command.text)
        val targetRoute =
            resolveWebhookRoute(parsedCommand)
                ?: resolveImageRoute(command.messageType)
                ?: return RoutingResult.SKIPPED
        val webhookUrl = Configurable.webhookEndpointFor(targetRoute).takeIf { it.isNotBlank() }
        if (webhookUrl.isNullOrBlank()) {
            IrisLogger.error("[H2cDispatcher] No webhook URL configured for route: $targetRoute")
            return RoutingResult.SKIPPED
        }

        val delivery = buildQueuedDelivery(command.copy(text = parsedCommand.normalizedText), webhookUrl, targetRoute)
        if (!registerDelivery(delivery)) {
            IrisLogger.error(
                "[H2cDispatcher] Failed to enqueue delivery: route=$targetRoute, messageId=${delivery.messageId}",
            )
            return RoutingResult.RETRY_LATER
        }

        return RoutingResult.ACCEPTED
    }

    override fun close() {
        started = false
        val routeStates = routeDispatchers.all().toList()
        routeStates.forEach { it.dispatchChannel.close() }
        runBlocking {
            routeStates.forEach { routeState ->
                val completed =
                    runCatching {
                        withTimeoutOrNull(WORKER_SHUTDOWN_TIMEOUT_MS) {
                            routeState.workerJob.join()
                            true
                        } == true
                    }.getOrElse {
                        IrisLogger.error(
                            "[H2cDispatcher] Failed waiting for route=${routeState.route} worker shutdown: ${it.message}",
                        )
                        false
                    }
                if (!completed) {
                    IrisLogger.error(
                        "[H2cDispatcher] Worker shutdown timed out for route=${routeState.route}; " +
                            "remaining in-flight delivery will be cancelled.",
                    )
                    routeState.workerJob.cancelAndJoin()
                }
            }
            scopeJob.cancelAndJoin()
        }
        sharedDispatcher.executorService.shutdown()
        sharedConnectionPool.evictAll()
    }

    private fun registerDelivery(delivery: QueuedDelivery): Boolean {
        val routeState = routeDispatchers.get(delivery.route)
        if (!routeState.queuedMessageIds.add(delivery.messageId)) {
            return true
        }

        if (submitDelivery(delivery, routeState)) {
            return true
        }

        routeState.queuedMessageIds.remove(delivery.messageId)
        return false
    }

    private fun submitDelivery(
        delivery: QueuedDelivery,
        routeState: RouteDispatchState = routeDispatchers.get(delivery.route),
    ): Boolean {
        if (!started) {
            return false
        }

        val sendResult = routeState.dispatchChannel.trySend(delivery)
        if (sendResult.isSuccess) {
            return true
        }
        if (sendResult.isClosed) {
            return false
        }

        IrisLogger.error(
            "[H2cDispatcher] Dispatch queue saturated: " +
                "route=${delivery.route}, messageId=${delivery.messageId}",
        )
        return false
    }

    private suspend fun processQueuedDelivery(delivery: QueuedDelivery) {
        val routeState = routeDispatchers.get(delivery.route)
        var attempt = 0

        while (true) {
            val outcome =
                try {
                    dispatchAttempt(delivery, attempt)
                } catch (e: Exception) {
                    IrisLogger.error(
                        "[H2cDispatcher] Dispatch worker error: route=${delivery.route}, " +
                            "messageId=${delivery.messageId}, attempt=${attempt + 1}: ${e.message}",
                    )
                    DeliveryOutcome.RETRY_LATER
                }

            when (outcome) {
                DeliveryOutcome.SUCCESS,
                DeliveryOutcome.DROP,
                -> {
                    routeState.queuedMessageIds.remove(delivery.messageId)
                    val tag = if (outcome == DeliveryOutcome.SUCCESS) "completed" else "dropped"
                    val log = "[H2cDispatcher] Delivery $tag: route=${delivery.route}, messageId=${delivery.messageId}"
                    if (outcome == DeliveryOutcome.SUCCESS) IrisLogger.debug(log) else IrisLogger.error(log)
                    return
                }

                DeliveryOutcome.RETRY_LATER -> {
                    when (
                        val retrySchedule =
                            nextDeliveryRetrySchedule(
                                attempt = attempt,
                                maxDeliveryAttempts = maxDeliveryAttempts,
                                backoffDelayProvider = backoffDelayProvider,
                            )
                    ) {
                        is DeliveryRetrySchedule.RetryAttempt -> {
                            IrisLogger.error(
                                "[H2cDispatcher] Delivery retry scheduled: route=${delivery.route}, " +
                                    "messageId=${delivery.messageId}, " +
                                    "nextAttempt=${retrySchedule.nextAttempt + 1}/$maxDeliveryAttempts, " +
                                    "delayMs=${retrySchedule.delayMs}",
                            )
                            attempt = retrySchedule.nextAttempt
                            delay(retrySchedule.delayMs)
                        }

                        is DeliveryRetrySchedule.Exhausted -> {
                            IrisLogger.error(
                                "[H2cDispatcher] Exhausted retries: route=${delivery.route}, " +
                                    "messageId=${delivery.messageId}",
                            )
                            routeState.queuedMessageIds.remove(delivery.messageId)
                            return
                        }
                    }
                }
            }
        }
    }

    private suspend fun dispatchAttempt(
        delivery: QueuedDelivery,
        attempt: Int,
    ): DeliveryOutcome {
        val attemptLabel = "${attempt + 1}/$maxDeliveryAttempts"
        return attemptDispatch(delivery, attemptLabel)
    }

    private suspend fun attemptDispatch(
        delivery: QueuedDelivery,
        attemptLabel: String,
    ): DeliveryOutcome =
        try {
            IrisLogger.debug(
                "[H2cDispatcher] Dispatch attempt started: route=${delivery.route}, url=${delivery.url}, " +
                    "messageId=${delivery.messageId}, attempt=$attemptLabel",
            )
            val request =
                Request
                    .Builder()
                    .url(delivery.url)
                    .post(delivery.payloadJson.toRequestBody(APPLICATION_JSON.toMediaType()))
                    .header(HEADER_IRIS_MESSAGE_ID, delivery.messageId)
                    .header(HEADER_IRIS_ROUTE, delivery.route)
                    .apply {
                        val webhookToken = Configurable.webhookToken
                        if (webhookToken.isNotBlank()) {
                            header(HEADER_IRIS_TOKEN, webhookToken)
                        }
                    }.build()

            val statusCode = executeRequest(request, delivery.url)
            classifyResponse(delivery, attemptLabel, statusCode)
        } catch (e: Exception) {
            IrisLogger.error(
                "[H2cDispatcher] Dispatch exception: route=${delivery.route}, url=${delivery.url}, " +
                    "messageId=${delivery.messageId}, attempt=$attemptLabel, " +
                    "exceptionClass=${e.javaClass.name}, message=${e.message ?: NONE}, " +
                    "causeClass=${e.cause?.javaClass?.name ?: NONE}, causeMessage=${e.cause?.message ?: NONE}",
            )
            DeliveryOutcome.RETRY_LATER
        }

    private suspend fun executeRequest(
        request: Request,
        webhookUrl: String,
    ): Int =
        suspendCancellableCoroutine { continuation ->
            val call = clientFor(webhookUrl).newCall(request)
            continuation.invokeOnCancellation {
                call.cancel()
            }
            call.enqueue(
                object : Callback {
                    override fun onFailure(
                        call: Call,
                        e: IOException,
                    ) {
                        if (!continuation.isActive) {
                            return
                        }
                        continuation.resumeWithException(e)
                    }

                    override fun onResponse(
                        call: Call,
                        response: Response,
                    ) {
                        response.use {
                            if (!continuation.isActive) {
                                return
                            }
                            continuation.resume(it.code)
                        }
                    }
                },
            )
        }

    private fun classifyResponse(
        delivery: QueuedDelivery,
        attemptLabel: String,
        statusCode: Int,
    ): DeliveryOutcome {
        IrisLogger.debug(
            "[H2cDispatcher] Response received: route=${delivery.route}, url=${delivery.url}, " +
                "messageId=${delivery.messageId}, attempt=$attemptLabel, status=$statusCode",
        )

        if (statusCode in 200..299) {
            IrisLogger.debug(
                "[H2cDispatcher] Dispatch success: route=${delivery.route}, url=${delivery.url}, " +
                    "messageId=${delivery.messageId}, attempt=$attemptLabel, status=$statusCode",
            )
            return DeliveryOutcome.SUCCESS
        }

        if (!shouldRetryStatus(statusCode)) {
            IrisLogger.error(
                "[H2cDispatcher] Non-retriable status $statusCode for route=${delivery.route}, " +
                    "url=${delivery.url}, messageId=${delivery.messageId}, attempt=$attemptLabel",
            )
            return DeliveryOutcome.DROP
        }

        IrisLogger.error(
            "[H2cDispatcher] Retriable status $statusCode for route=${delivery.route}, " +
                "url=${delivery.url}, messageId=${delivery.messageId}, attempt=$attemptLabel",
        )
        return DeliveryOutcome.RETRY_LATER
    }

    private fun buildQueuedDelivery(
        command: RoutingCommand,
        webhookUrl: String,
        route: String,
    ): QueuedDelivery {
        val messageId = "kakao-log-${command.sourceLogId}-$route"
        return QueuedDelivery(
            url = webhookUrl,
            messageId = messageId,
            route = route,
            payloadJson =
                buildJsonObject {
                    put("route", route)
                    put("messageId", messageId)
                    put("sourceLogId", command.sourceLogId)
                    put("text", command.text)
                    put("room", command.room)
                    put("sender", command.sender)
                    put("userId", command.userId)
                    if (!command.chatLogId.isNullOrBlank()) {
                        put("chatLogId", command.chatLogId)
                    }
                    if (!command.roomType.isNullOrBlank()) {
                        put("roomType", command.roomType)
                    }
                    if (!command.roomLinkId.isNullOrBlank()) {
                        put("roomLinkId", command.roomLinkId)
                    }
                    if (!command.threadId.isNullOrBlank()) {
                        put("threadId", command.threadId)
                    }
                    command.threadScope?.let { put("threadScope", it) }
                    if (!command.messageType.isNullOrBlank()) {
                        put("type", command.messageType)
                    }
                    if (!command.attachment.isNullOrBlank()) {
                        put("attachment", command.attachment)
                    }
                }.toString(),
        )
    }

    private enum class DeliveryOutcome {
        SUCCESS,
        DROP,
        RETRY_LATER,
    }

    private enum class WebhookTransport {
        H2C,
        HTTP1,
    }

    companion object {
        private const val WORKER_SHUTDOWN_TIMEOUT_MS = 10_000L
        private const val DISPATCH_QUEUE_CAPACITY = 64
        private const val MAX_CONCURRENT_REQUESTS = 8
        private const val MAX_CONCURRENT_REQUESTS_PER_HOST = 4
        private const val MAX_IDLE_CONNECTIONS = 4
        private const val KEEP_ALIVE_DURATION_MS = 30_000L
        internal const val MAX_DELIVERY_ATTEMPTS = 6

        private const val HEADER_IRIS_TOKEN = "X-Iris-Token"
        private const val HEADER_IRIS_MESSAGE_ID = "X-Iris-Message-Id"
        private const val HEADER_IRIS_ROUTE = "X-Iris-Route"
        private const val APPLICATION_JSON = "application/json"
        private const val NONE = "<none>"

        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val REQUEST_TIMEOUT_MS = 30_000L
        private const val SOCKET_TIMEOUT_MS = 30_000L
    }
}

internal class RouteDispatchRegistry<T>(
    private val factory: (String) -> T,
) {
    private val states = ConcurrentHashMap<String, T>()

    fun get(route: String): T = states.computeIfAbsent(route, factory)

    fun all(): Collection<T> = states.values
}

private data class RouteDispatchState(
    val route: String,
    val queuedMessageIds: MutableSet<String>,
    val dispatchChannel: Channel<QueuedDelivery>,
    val workerJob: Job,
)

internal fun shouldRetryStatus(statusCode: Int): Boolean = statusCode == 408 || statusCode == 429 || statusCode >= 500

private data class QueuedDelivery(
    val url: String,
    val messageId: String,
    val route: String,
    val payloadJson: String,
)
