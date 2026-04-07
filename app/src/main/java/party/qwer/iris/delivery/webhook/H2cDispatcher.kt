package party.qwer.iris.delivery.webhook

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import party.qwer.iris.CommandParser
import party.qwer.iris.ConfigProvider
import party.qwer.iris.IrisLogger
import party.qwer.iris.delivery.webhook.DeliveryRetrySchedule
import party.qwer.iris.delivery.webhook.RoutingCommand
import party.qwer.iris.delivery.webhook.RoutingResult
import party.qwer.iris.delivery.webhook.WebhookDelivery
import party.qwer.iris.delivery.webhook.WebhookDeliveryClient
import party.qwer.iris.delivery.webhook.WebhookHttpClientFactory
import party.qwer.iris.delivery.webhook.WebhookRequestFactory
import party.qwer.iris.delivery.webhook.WebhookTransport
import party.qwer.iris.delivery.webhook.buildWebhookPayload
import party.qwer.iris.delivery.webhook.nextBackoffDelayMs
import party.qwer.iris.delivery.webhook.nextDeliveryRetrySchedule
import party.qwer.iris.delivery.webhook.resolveImageRoute
import party.qwer.iris.delivery.webhook.resolveWebhookRoute
import party.qwer.iris.delivery.webhook.resolveWebhookTransport
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class H2cDispatcher internal constructor(
    private val config: ConfigProvider,
    transportOverride: String? = null,
    queueCapacityOverride: Int? = null,
    maxDeliveryAttemptsOverride: Int? = null,
    routeConcurrencyOverride: Int? = null,
    backoffDelayProviderOverride: (Int) -> Long = ::nextBackoffDelayMs,
) : Closeable {
    private val scopeJob = SupervisorJob()
    private val coroutineScope = CoroutineScope(scopeJob + Dispatchers.IO)
    private val routeQueueCapacity = queueCapacityOverride ?: DISPATCH_QUEUE_CAPACITY
    private val maxDeliveryAttempts = maxDeliveryAttemptsOverride ?: MAX_DELIVERY_ATTEMPTS
    private val routeConcurrency = (routeConcurrencyOverride ?: DEFAULT_ROUTE_CONCURRENCY).also { require(it > 0) { "routeConcurrency must be positive, got $it" } }
    private val backoffDelayProvider = backoffDelayProviderOverride
    private val transport = resolveWebhookTransport(transportOverride)

    private val sharedDispatcher =
        Dispatcher().apply {
            when (transport) {
                WebhookTransport.H2C -> {
                    maxRequests = DISPATCH_QUEUE_CAPACITY * MAX_ROUTE_MULTIPLIER
                    maxRequestsPerHost = DISPATCH_QUEUE_CAPACITY
                }
                WebhookTransport.HTTP1 -> {
                    maxRequests = MAX_CONCURRENT_REQUESTS
                    maxRequestsPerHost = MAX_CONCURRENT_REQUESTS
                }
            }
        }
    private val sharedConnectionPool =
        ConnectionPool(
            MAX_IDLE_CONNECTIONS,
            KEEP_ALIVE_DURATION_MS,
            TimeUnit.MILLISECONDS,
        )
    private val clientFactory =
        WebhookHttpClientFactory(
            transport,
            sharedDispatcher,
            sharedConnectionPool,
        )
    private val requestFactory = WebhookRequestFactory(config)
    private val deliveryClient = WebhookDeliveryClient(clientFactory)
    private val routeDispatchers = RouteDispatchRegistry(::createRouteDispatchState)

    @Volatile
    private var started = true

    private fun createRouteDispatchState(route: String): RouteDispatchState {
        val dispatchChannel = Channel<QueuedDelivery>(capacity = routeQueueCapacity)
        val queuedMessageIds = ConcurrentHashMap.newKeySet<String>()
        val workerJob =
            coroutineScope.launch {
                val concurrencyLimit = Semaphore(routeConcurrency)
                for (delivery in dispatchChannel) {
                    concurrencyLimit.acquire()
                    launch {
                        try {
                            processQueuedDelivery(delivery)
                        } finally {
                            concurrencyLimit.release()
                        }
                    }
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
            resolveWebhookRoute(parsedCommand, config)
                ?: resolveEventRoute(command.messageType)
                ?: resolveImageRoute(command.messageType, config)
                ?: return RoutingResult.SKIPPED
        val webhookUrl = config.webhookEndpointFor(targetRoute).takeIf { it.isNotBlank() }
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
            routeStates
                .map { routeState ->
                    async {
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
                }.awaitAll()
            scopeJob.cancelAndJoin()
        }
        sharedDispatcher.executorService.shutdown()
        if (!sharedDispatcher.executorService.awaitTermination(5, TimeUnit.SECONDS)) {
            IrisLogger.error("[H2cDispatcher] OkHttp executor did not terminate within 5s; forcing shutdown")
            sharedDispatcher.executorService.shutdownNow()
        }
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
        val outcome =
            try {
                dispatchAttempt(delivery, delivery.attempt)
            } catch (e: Exception) {
                IrisLogger.error(
                    "[H2cDispatcher] Dispatch worker error: route=${delivery.route}, " +
                        "messageId=${delivery.messageId}, attempt=${delivery.attempt + 1}: ${e.message}",
                )
                DeliveryOutcome.RETRY_LATER
            }

        when (outcome) {
            DeliveryOutcome.SUCCESS,
            DeliveryOutcome.DROP,
            -> {
                routeState.queuedMessageIds.remove(delivery.messageId)
                when (outcome) {
                    DeliveryOutcome.SUCCESS ->
                        IrisLogger.debug(
                            "[H2cDispatcher] Delivery completed: route=${delivery.route}, messageId=${delivery.messageId}",
                        )
                    else ->
                        IrisLogger.error(
                            "[H2cDispatcher] Delivery dropped: route=${delivery.route}, messageId=${delivery.messageId}",
                        )
                }
                return
            }

            DeliveryOutcome.RETRY_LATER -> {
                when (
                    val retrySchedule =
                        nextDeliveryRetrySchedule(
                            attempt = delivery.attempt,
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
                        scheduleRetry(delivery.copy(attempt = retrySchedule.nextAttempt), retrySchedule.delayMs)
                    }

                    is DeliveryRetrySchedule.Exhausted -> {
                        IrisLogger.error(
                            "[H2cDispatcher] Exhausted retries: route=${delivery.route}, " +
                                "messageId=${delivery.messageId}",
                        )
                        routeState.queuedMessageIds.remove(delivery.messageId)
                    }
                }
            }
        }
    }

    private fun scheduleRetry(
        delivery: QueuedDelivery,
        delayMs: Long,
    ) {
        coroutineScope.launch {
            try {
                delay(delayMs)
                val routeState = routeDispatchers.get(delivery.route)
                if (!submitDelivery(delivery, routeState)) {
                    routeState.queuedMessageIds.remove(delivery.messageId)
                    IrisLogger.error(
                        "[H2cDispatcher] Failed to re-enqueue retry: route=${delivery.route}, messageId=${delivery.messageId}",
                    )
                }
            } catch (_: CancellationException) {
                routeDispatchers.get(delivery.route).queuedMessageIds.remove(delivery.messageId)
            }
        }
    }

    private suspend fun dispatchAttempt(
        delivery: QueuedDelivery,
        attempt: Int,
    ): DeliveryOutcome = attemptDispatch(delivery, "${attempt + 1}/$maxDeliveryAttempts")

    private suspend fun attemptDispatch(
        delivery: QueuedDelivery,
        attemptLabel: String,
    ): DeliveryOutcome =
        try {
            IrisLogger.debug(
                "[H2cDispatcher] Dispatch attempt started: route=${delivery.route}, url=${delivery.url}, " +
                    "messageId=${delivery.messageId}, attempt=$attemptLabel",
            )
            val request = requestFactory.create(delivery)
            val statusCode = deliveryClient.execute(request, delivery.url)
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
        val messageId = buildRoutingMessageId(command, route)
        return QueuedDelivery(
            url = webhookUrl,
            messageId = messageId,
            route = route,
            payloadJson = buildWebhookPayload(command, route, messageId),
            attempt = 0,
        )
    }

    private enum class DeliveryOutcome {
        SUCCESS,
        DROP,
        RETRY_LATER,
    }

    companion object {
        private const val WORKER_SHUTDOWN_TIMEOUT_MS = 10_000L
        private const val DISPATCH_QUEUE_CAPACITY = 64
        private const val MAX_ROUTE_MULTIPLIER = 4
        private const val MAX_CONCURRENT_REQUESTS = 8
        private const val MAX_IDLE_CONNECTIONS = 4
        private const val KEEP_ALIVE_DURATION_MS = 30_000L
        internal const val MAX_DELIVERY_ATTEMPTS = WebhookDeliveryPolicy.DEFAULT_MAX_DELIVERY_ATTEMPTS
        private const val DEFAULT_ROUTE_CONCURRENCY = 4

        private const val NONE = "<none>"
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

private typealias QueuedDelivery = WebhookDelivery
