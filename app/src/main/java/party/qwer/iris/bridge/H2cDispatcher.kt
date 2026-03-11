package party.qwer.iris.bridge

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Protocol
import party.qwer.iris.Configurable
import party.qwer.iris.IrisLogger
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

class H2cDispatcher : Closeable {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    private val webhookTransport: WebhookTransport =
        run {
            val raw =
                System
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

    private val h2cClient: HttpClient = createClient(listOf(Protocol.H2_PRIOR_KNOWLEDGE))
    private val http1Client: HttpClient = createClient(listOf(Protocol.HTTP_1_1))

    private fun createClient(okhttpProtocols: List<Protocol>): HttpClient =
        HttpClient(OkHttp) {
            expectSuccess = false
            install(HttpTimeout) {
                connectTimeoutMillis = CONNECT_TIMEOUT_MS
                requestTimeoutMillis = REQUEST_TIMEOUT_MS
                socketTimeoutMillis = SOCKET_TIMEOUT_MS
            }
            engine {
                config {
                    protocols(okhttpProtocols)
                    retryOnConnectionFailure(true)
                    dispatcher(
                        Dispatcher().apply {
                            maxRequests = MAX_CONCURRENT_REQUESTS
                            maxRequestsPerHost = MAX_CONCURRENT_REQUESTS_PER_HOST
                        },
                    )
                    connectionPool(
                        ConnectionPool(
                            MAX_IDLE_CONNECTIONS,
                            KEEP_ALIVE_DURATION_MS,
                            TimeUnit.MILLISECONDS,
                        ),
                    )
                }
            }
        }

    private fun clientFor(webhookUrl: String): HttpClient {
        if (webhookUrl.startsWith("https://")) {
            return http1Client
        }

        return when (webhookTransport) {
            WebhookTransport.H2C -> h2cClient
            WebhookTransport.HTTP1 -> http1Client
        }
    }

    @Volatile
    private var started = false
    private val outboxDir = File(OUTBOX_DIR_PATH)
    private val queuedPaths =
        java.util.concurrent.ConcurrentHashMap
            .newKeySet<String>()
    private val deliveryQueue = LinkedBlockingQueue<DeliveryWorkItem>()
    private val workerCounter = AtomicInteger(0)
    private val workerPool: ExecutorService =
        Executors.newFixedThreadPool(
            1,
            ThreadFactory { runnable ->
                Thread(runnable, "Iris-H2cDispatcher-${workerCounter.incrementAndGet()}").apply {
                    isDaemon = true
                }
            },
        )

    init {
        if (!outboxDir.exists()) {
            check(outboxDir.mkdirs() || outboxDir.exists()) {
                "Failed to create outbox directory: ${outboxDir.absolutePath}"
            }
        }

        val pendingFiles =
            outboxDir
                .listFiles { file -> file.isFile && file.extension == OUTBOX_FILE_EXTENSION }
                ?.sortedBy { it.name }
                .orEmpty()
        pendingFiles.forEach { file ->
            if (queuedPaths.add(file.absolutePath)) {
                deliveryQueue.put(DeliveryWorkItem.Persisted(file, null))
            }
        }

        if (!started) {
            synchronized(this) {
                if (!started) {
                    started = true
                    workerPool.submit {
                        runWorkerLoop()
                    }
                }
            }
        }
    }

    fun route(command: RoutingCommand): RoutingResult {
        val targetRoute =
            when {
                command.text.startsWith(PREFIX_COMMENT) -> null
                command.text.startsWith(PREFIX_GENERIC) -> ROUTE_HOLOLIVE
                else -> null
            } ?: return RoutingResult.SKIPPED
        val webhookUrl = Configurable.defaultWebhookEndpoint.takeIf { it.isNotBlank() }
        if (webhookUrl.isNullOrBlank()) {
            IrisLogger.error("[H2cDispatcher] No webhook URL configured for route: $targetRoute")
            return RoutingResult.SKIPPED
        }

        val delivery = buildQueuedDelivery(command, webhookUrl, targetRoute)
        try {
            val outboxFile = outboxFileFor(delivery)
            if (!outboxFile.exists()) {
                persistDelivery(outboxFile, delivery)
            }
            if (queuedPaths.add(outboxFile.absolutePath)) {
                deliveryQueue.put(DeliveryWorkItem.Persisted(outboxFile, delivery))
            }
            return RoutingResult.ACCEPTED
        } catch (e: Exception) {
            IrisLogger.error(
                "[H2cDispatcher] Failed to persist delivery: route=$targetRoute, " +
                    "messageId=${delivery.messageId}: ${e.message}",
            )
            return RoutingResult.RETRY_LATER
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return RoutingResult.RETRY_LATER
        }
    }

    override fun close() {
        started = false
        workerPool.shutdown()
        runCatching {
            if (!workerPool.awaitTermination(WORKER_SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                IrisLogger.error(
                    "[H2cDispatcher] Worker shutdown timed out; pending outbox files will be recovered on next start.",
                )
            }
        }
        h2cClient.close()
        http1Client.close()
    }

    private fun runWorkerLoop() {
        while (started || deliveryQueue.isNotEmpty()) {
            try {
                val item = deliveryQueue.poll(QUEUE_POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                if (item != null) {
                    handleQueuedItem(item)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            } catch (e: Exception) {
                IrisLogger.error("[H2cDispatcher] Worker loop error: ${e.message}")
                try {
                    Thread.sleep(WORKER_RETRY_DELAY_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            }
        }
    }

    @Throws(InterruptedException::class)
    private fun handleQueuedItem(item: DeliveryWorkItem) {
        val resolved = resolveQueuedDelivery(item) ?: return

        val outcome =
            try {
                dispatchWithRetry(resolved.delivery)
            } finally {
                if (resolved.persistedFile != null) {
                    queuedPaths.remove(resolved.persistedFile.absolutePath)
                }
            }

        handleDeliveryOutcome(resolved, outcome)
    }

    private fun resolveQueuedDelivery(item: DeliveryWorkItem): ResolvedDelivery? =
        when (item) {
            is DeliveryWorkItem.Memory -> ResolvedDelivery(item.delivery, null)
            is DeliveryWorkItem.Persisted ->
                item.delivery?.let { delivery ->
                    ResolvedDelivery(delivery, item.file)
                } ?: runCatching {
                    json.decodeFromString(QueuedDelivery.serializer(), item.file.readText())
                }.map { delivery ->
                    ResolvedDelivery(delivery, item.file)
                }.getOrElse { error ->
                    IrisLogger.error("[H2cDispatcher] Invalid outbox file ${item.file.name}: ${error.message}")
                    item.file.delete()
                    null
                }
        }

    @Throws(InterruptedException::class)
    private fun handleDeliveryOutcome(
        resolved: ResolvedDelivery,
        outcome: DeliveryOutcome,
    ) {
        when (outcome) {
            DeliveryOutcome.SUCCESS -> handleSuccessfulDelivery(resolved)
            DeliveryOutcome.DROP -> handleDroppedDelivery(resolved)
            DeliveryOutcome.RETRY_LATER -> handleRetryLaterDelivery(resolved)
        }
    }

    private fun handleSuccessfulDelivery(resolved: ResolvedDelivery) {
        if (resolved.persistedFile != null && !resolved.persistedFile.delete()) {
            IrisLogger.error(
                "[H2cDispatcher] Delivered but failed to delete outbox file: ${resolved.persistedFile.name}",
            )
        }
        IrisLogger.debug(
            "[H2cDispatcher] Delivery completed: route=${resolved.delivery.route}, " +
                "messageId=${resolved.delivery.messageId}",
        )
    }

    private fun handleDroppedDelivery(resolved: ResolvedDelivery) {
        if (resolved.persistedFile != null && !resolved.persistedFile.delete()) {
            IrisLogger.error(
                "[H2cDispatcher] Dropped but failed to delete outbox file: ${resolved.persistedFile.name}",
            )
        }
        IrisLogger.error(
            "[H2cDispatcher] Delivery dropped: route=${resolved.delivery.route}, " +
                "messageId=${resolved.delivery.messageId}",
        )
    }

    @Throws(InterruptedException::class)
    private fun handleRetryLaterDelivery(resolved: ResolvedDelivery) {
        IrisLogger.error(
            "[H2cDispatcher] Delivery deferred for retry: route=${resolved.delivery.route}, " +
                "messageId=${resolved.delivery.messageId}",
        )

        val retryFile = retryFileFor(resolved)
        if (started || retryFile?.exists() == true) {
            Thread.sleep(RETRY_LATER_REQUEUE_DELAY_MS)
            if (retryFile != null) {
                if (queuedPaths.add(retryFile.absolutePath)) {
                    deliveryQueue.put(DeliveryWorkItem.Persisted(retryFile, null))
                }
            } else {
                deliveryQueue.put(DeliveryWorkItem.Memory(resolved.delivery))
            }
        }
    }

    private fun retryFileFor(resolved: ResolvedDelivery): File? =
        resolved.persistedFile ?: runCatching {
            val outboxFile = outboxFileFor(resolved.delivery)
            if (!outboxFile.exists()) {
                persistDelivery(outboxFile, resolved.delivery)
            }
            outboxFile
        }.getOrElse { error ->
            IrisLogger.error(
                "[H2cDispatcher] Failed to persist retry delivery: route=${resolved.delivery.route}, " +
                    "messageId=${resolved.delivery.messageId}: ${error.message}",
            )
            null
        }

    private fun dispatchWithRetry(delivery: QueuedDelivery): DeliveryOutcome {
        val body = buildRequestBody(delivery)

        for (attempt in 0 until MAX_DELIVERY_ATTEMPTS) {
            val attemptLabel = "${attempt + 1}/$MAX_DELIVERY_ATTEMPTS"
            val immediateOutcome = attemptDispatch(delivery, body, attemptLabel)
            if (immediateOutcome != null) {
                return immediateOutcome
            }

            if (attempt == MAX_DELIVERY_ATTEMPTS - 1) {
                break
            }

            try {
                Thread.sleep(nextBackoffDelayMs(attempt))
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return DeliveryOutcome.RETRY_LATER
            }
        }

        IrisLogger.error(
            "[H2cDispatcher] Exhausted retries: route=${delivery.route}, messageId=${delivery.messageId}",
        )
        return DeliveryOutcome.RETRY_LATER
    }

    private fun buildRequestBody(delivery: QueuedDelivery): String =
        buildJsonObject {
            put("route", delivery.route)
            put("messageId", delivery.messageId)
            put("sourceLogId", delivery.sourceLogId)
            put("text", delivery.text)
            put("room", delivery.room)
            put("sender", delivery.sender)
            put("userId", delivery.userId)
            put("threadId", delivery.threadId ?: "")
        }.toString()

    private fun attemptDispatch(
        delivery: QueuedDelivery,
        body: String,
        attemptLabel: String,
    ): DeliveryOutcome? =
        try {
            IrisLogger.debug(
                "[H2cDispatcher] Dispatch attempt started: route=${delivery.route}, url=${delivery.url}, " +
                    "messageId=${delivery.messageId}, attempt=$attemptLabel",
            )
            runBlocking {
                clientFor(delivery.url)
                    .preparePost(delivery.url) {
                        header(HEADER_CONTENT_TYPE, APPLICATION_JSON)
                        setBody(body)
                        header(HEADER_IRIS_TOKEN, Configurable.webhookToken)
                        header(HEADER_IRIS_MESSAGE_ID, delivery.messageId)
                        header(HEADER_IRIS_ROUTE, delivery.route)
                    }.execute { response ->
                        classifyResponse(delivery, attemptLabel, response.status.value)
                    }
            }
        } catch (e: Exception) {
            IrisLogger.error(
                "[H2cDispatcher] Dispatch exception: route=${delivery.route}, url=${delivery.url}, " +
                    "messageId=${delivery.messageId}, attempt=$attemptLabel, " +
                    "exceptionClass=${e.javaClass.name}, message=${e.message ?: NONE}, " +
                    "causeClass=${e.cause?.javaClass?.name ?: NONE}, causeMessage=${e.cause?.message ?: NONE}",
            )
            null
        }

    private fun classifyResponse(
        delivery: QueuedDelivery,
        attemptLabel: String,
        statusCode: Int,
    ): DeliveryOutcome? {
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
        return null
    }

    private fun persistDelivery(
        outboxFile: File,
        delivery: QueuedDelivery,
    ) {
        val tempFile = File(outboxFile.parentFile, "${outboxFile.name}.tmp")
        val payload = json.encodeToString(QueuedDelivery.serializer(), delivery)
        FileOutputStream(tempFile).use { output ->
            output.write(payload.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        check(tempFile.renameTo(outboxFile)) {
            tempFile.delete()
            "Failed to promote outbox temp file: ${outboxFile.name}"
        }
    }

    private fun outboxFileFor(delivery: QueuedDelivery): File {
        val fileName =
            buildString {
                append(delivery.sourceLogId.toString().padStart(20, '0'))
                append(FILE_NAME_SEPARATOR)
                append(delivery.route.replace(FILE_NAME_SANITIZE_REGEX, "_"))
                append(FILE_NAME_SEPARATOR)
                append(delivery.messageId.replace(FILE_NAME_SANITIZE_REGEX, "_"))
                append('.')
                append(OUTBOX_FILE_EXTENSION)
            }
        return File(outboxDir, fileName)
    }

    private fun buildQueuedDelivery(
        command: RoutingCommand,
        webhookUrl: String,
        route: String,
    ): QueuedDelivery {
        val messageId = "kakao-log-${command.sourceLogId}-$route"
        return QueuedDelivery(
            sourceLogId = command.sourceLogId,
            url = webhookUrl,
            text = command.text,
            room = command.room,
            sender = command.sender,
            userId = command.userId,
            threadId = command.threadId,
            messageId = messageId,
            route = route,
        )
    }

    data class RoutingCommand(
        val text: String,
        val room: String,
        val sender: String,
        val userId: String,
        val sourceLogId: Long,
        val threadId: String? = null,
    )

    enum class RoutingResult {
        ACCEPTED,
        SKIPPED,
        RETRY_LATER,
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

    @Serializable
    private data class QueuedDelivery(
        val sourceLogId: Long,
        val url: String,
        val text: String,
        val room: String,
        val sender: String,
        val userId: String,
        val threadId: String?,
        val messageId: String,
        val route: String,
    )

    private sealed interface DeliveryWorkItem {
        data class Memory(
            val delivery: QueuedDelivery,
        ) : DeliveryWorkItem

        data class Persisted(
            val file: File,
            val delivery: QueuedDelivery?,
        ) : DeliveryWorkItem
    }

    private data class ResolvedDelivery(
        val delivery: QueuedDelivery,
        val persistedFile: File?,
    )

    companion object {
        private const val QUEUE_POLL_TIMEOUT_MS = 1_000L
        private const val WORKER_RETRY_DELAY_MS = 1_000L
        private const val WORKER_SHUTDOWN_TIMEOUT_MS = 10_000L
        private const val RETRY_LATER_REQUEUE_DELAY_MS = 5_000L
        private const val MAX_CONCURRENT_REQUESTS = 8
        private const val MAX_CONCURRENT_REQUESTS_PER_HOST = 4
        private const val MAX_IDLE_CONNECTIONS = 4
        private const val KEEP_ALIVE_DURATION_MS = 30_000L
        private const val MAX_DELIVERY_ATTEMPTS = 6

        private const val ROUTE_HOLOLIVE = "hololive"
        private const val PREFIX_COMMENT = "//"
        private const val PREFIX_GENERIC = "!"

        private const val HEADER_IRIS_TOKEN = "X-Iris-Token"
        private const val HEADER_IRIS_MESSAGE_ID = "X-Iris-Message-Id"
        private const val HEADER_IRIS_ROUTE = "X-Iris-Route"
        private const val HEADER_CONTENT_TYPE = "Content-Type"
        private const val APPLICATION_JSON = "application/json"
        private const val NONE = "<none>"

        private const val OUTBOX_DIR_PATH = "/data/local/tmp/iris-webhook-outbox"
        private const val OUTBOX_FILE_EXTENSION = "json"
        private const val FILE_NAME_SEPARATOR = "__"
        private val FILE_NAME_SANITIZE_REGEX = Regex("[^A-Za-z0-9._-]")

        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val REQUEST_TIMEOUT_MS = 30_000L
        private const val SOCKET_TIMEOUT_MS = 30_000L
    }
}

private fun shouldRetryStatus(statusCode: Int): Boolean = statusCode == 408 || statusCode == 429 || statusCode >= 500

private fun nextBackoffDelayMs(attempt: Int): Long {
    val cappedAttempt = attempt.coerceAtMost(5)
    val baseDelay = 1_000L shl cappedAttempt
    val boundedDelay = baseDelay.coerceAtMost(30_000L)
    val jitter = Random.nextLong(0, 500L + 1)
    return boundedDelay + jitter
}
