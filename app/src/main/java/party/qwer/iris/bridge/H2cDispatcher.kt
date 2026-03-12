package party.qwer.iris.bridge

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import party.qwer.iris.CommandKind
import party.qwer.iris.CommandParser
import party.qwer.iris.Configurable
import party.qwer.iris.IrisLogger
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
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

    private val h2cClient: OkHttpClient = createClient(listOf(Protocol.H2_PRIOR_KNOWLEDGE))
    private val http1Client: OkHttpClient = createClient(listOf(Protocol.HTTP_1_1))

    private fun createClient(okhttpProtocols: List<Protocol>): OkHttpClient =
        OkHttpClient
            .Builder()
            .protocols(okhttpProtocols)
            .retryOnConnectionFailure(true)
            .dispatcher(
                Dispatcher().apply {
                    maxRequests = MAX_CONCURRENT_REQUESTS
                    maxRequestsPerHost = MAX_CONCURRENT_REQUESTS_PER_HOST
                },
            ).connectionPool(
                ConnectionPool(
                    MAX_IDLE_CONNECTIONS,
                    KEEP_ALIVE_DURATION_MS,
                    TimeUnit.MILLISECONDS,
                ),
            ).connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(SOCKET_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()

    private fun clientFor(webhookUrl: String): OkHttpClient {
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
    private val admissionStore = DurableAdmissionStore(outboxDir, json)
    private val queuedAdmissionKeys =
        java.util.concurrent.ConcurrentHashMap
            .newKeySet<String>()
    private val workerCounter = AtomicInteger(0)
    private val schedulerCounter = AtomicInteger(0)
    private val dispatchExecutor: ThreadPoolExecutor =
        ThreadPoolExecutor(
            DISPATCH_WORKER_COUNT,
            DISPATCH_WORKER_COUNT,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue<Runnable>(DISPATCH_QUEUE_CAPACITY),
            ThreadFactory { runnable ->
                Thread(runnable, "Iris-H2cDispatcher-${workerCounter.incrementAndGet()}").apply {
                    isDaemon = true
                }
            },
        )
    private val retryScheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor(
            ThreadFactory { runnable ->
                Thread(runnable, "Iris-H2cDispatcher-Scheduler-${schedulerCounter.incrementAndGet()}").apply {
                    isDaemon = true
                }
            },
        )

    init {
        started = true
        admissionStore.recoverPending().forEach { storedDelivery ->
            if (!registerDelivery(storedDelivery)) {
                IrisLogger.error(
                    "[H2cDispatcher] Failed to re-register recovered delivery: " +
                        "route=${storedDelivery.delivery.route}, messageId=${storedDelivery.delivery.messageId}",
                )
            }
        }
    }

    fun route(command: RoutingCommand): RoutingResult {
        val parsedCommand = CommandParser.parse(command.text)
        val targetRoute =
            when (parsedCommand.kind) {
                CommandKind.WEBHOOK -> ROUTE_HOLOLIVE
                CommandKind.NONE,
                CommandKind.COMMENT,
                -> null
            } ?: return RoutingResult.SKIPPED
        val webhookUrl = Configurable.defaultWebhookEndpoint.takeIf { it.isNotBlank() }
        if (webhookUrl.isNullOrBlank()) {
            IrisLogger.error("[H2cDispatcher] No webhook URL configured for route: $targetRoute")
            return RoutingResult.SKIPPED
        }

        val delivery = buildQueuedDelivery(command.copy(text = parsedCommand.normalizedText), webhookUrl, targetRoute)
        try {
            val storedDelivery = admissionStore.admit(delivery)
            if (!registerDelivery(storedDelivery)) {
                IrisLogger.error(
                    "[H2cDispatcher] Failed to schedule admitted delivery: route=$targetRoute, " +
                        "messageId=${delivery.messageId}",
                )
                return RoutingResult.RETRY_LATER
            }
            return RoutingResult.ACCEPTED
        } catch (e: Exception) {
            IrisLogger.error(
                "[H2cDispatcher] Failed to admit delivery: route=$targetRoute, " +
                    "messageId=${delivery.messageId}: ${e.message}",
            )
            return RoutingResult.RETRY_LATER
        }
    }

    override fun close() {
        started = false
        retryScheduler.shutdownNow()
        dispatchExecutor.shutdown()
        runCatching {
            if (!dispatchExecutor.awaitTermination(WORKER_SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                IrisLogger.error(
                    "[H2cDispatcher] Worker shutdown timed out; pending outbox files will be recovered on next start.",
                )
            }
        }
        h2cClient.dispatcher.executorService.shutdown()
        h2cClient.connectionPool.evictAll()
        http1Client.dispatcher.executorService.shutdown()
        http1Client.connectionPool.evictAll()
    }

    private fun registerDelivery(storedDelivery: StoredDelivery): Boolean {
        if (!queuedAdmissionKeys.add(storedDelivery.handle.key)) {
            return true
        }

        if (submitDispatchAttempt(DispatchAttempt(storedDelivery))) {
            return true
        }

        queuedAdmissionKeys.remove(storedDelivery.handle.key)
        return false
    }

    private fun submitDispatchAttempt(attempt: DispatchAttempt): Boolean {
        if (!started) {
            return false
        }
        if (!admissionStore.isRecoverable(attempt.item.handle)) {
            queuedAdmissionKeys.remove(attempt.item.handle.key)
            return true
        }

        return try {
            dispatchExecutor.execute {
                processDispatchAttempt(attempt)
            }
            true
        } catch (_: RejectedExecutionException) {
            IrisLogger.error(
                "[H2cDispatcher] Dispatch queue saturated, scheduling retry: " +
                    "route=${attempt.item.delivery.route}, messageId=${attempt.item.delivery.messageId}, " +
                    "attempt=${attempt.attempt + 1}/$MAX_DELIVERY_ATTEMPTS",
            )
            scheduleDispatchAttempt(attempt, QUEUE_FULL_RETRY_DELAY_MS)
        }
    }

    private fun scheduleDispatchAttempt(
        attempt: DispatchAttempt,
        delayMs: Long,
    ): Boolean {
        if (!started) {
            return false
        }

        return runCatching {
            retryScheduler.schedule(
                {
                    if (!started || !admissionStore.isRecoverable(attempt.item.handle)) {
                        return@schedule
                    }

                    if (!submitDispatchAttempt(attempt) && started) {
                        IrisLogger.error(
                            "[H2cDispatcher] Failed to submit scheduled delivery: " +
                                "route=${attempt.item.delivery.route}, " +
                                "messageId=${attempt.item.delivery.messageId}, attempt=${attempt.attempt + 1}",
                        )
                    }
                },
                delayMs,
                TimeUnit.MILLISECONDS,
            )
            true
        }.getOrElse { error ->
            IrisLogger.error(
                "[H2cDispatcher] Failed to schedule delivery attempt: " +
                    "route=${attempt.item.delivery.route}, " +
                    "messageId=${attempt.item.delivery.messageId}, delayMs=$delayMs: ${error.message}",
            )
            false
        }
    }

    private fun processDispatchAttempt(attempt: DispatchAttempt) {
        val item = attempt.item
        val outcome =
            try {
                dispatchAttempt(item.delivery, attempt.attempt)
            } catch (e: Exception) {
                IrisLogger.error(
                    "[H2cDispatcher] Dispatch worker error: route=${item.delivery.route}, " +
                        "messageId=${item.delivery.messageId}, attempt=${attempt.attempt + 1}: ${e.message}",
                )
                DeliveryOutcome.RETRY_LATER
            }

        when (outcome) {
            DeliveryOutcome.SUCCESS -> {
                queuedAdmissionKeys.remove(item.handle.key)
                handleSuccessfulDelivery(item)
            }
            DeliveryOutcome.DROP -> {
                queuedAdmissionKeys.remove(item.handle.key)
                handleDroppedDelivery(item)
            }
            DeliveryOutcome.RETRY_LATER -> handleRetryLaterDelivery(attempt)
        }
    }

    private fun handleSuccessfulDelivery(item: StoredDelivery) {
        if (!admissionStore.complete(item.handle)) {
            IrisLogger.error(
                "[H2cDispatcher] Delivered but failed to retire durable admission: ${item.handle.key}",
            )
        }
        IrisLogger.debug(
            "[H2cDispatcher] Delivery completed: route=${item.delivery.route}, " +
                "messageId=${item.delivery.messageId}",
        )
    }

    private fun handleDroppedDelivery(item: StoredDelivery) {
        if (!admissionStore.complete(item.handle)) {
            IrisLogger.error(
                "[H2cDispatcher] Dropped but failed to retire durable admission: ${item.handle.key}",
            )
        }
        IrisLogger.error(
            "[H2cDispatcher] Delivery dropped: route=${item.delivery.route}, " +
                "messageId=${item.delivery.messageId}",
        )
    }

    private fun handleRetryLaterDelivery(attempt: DispatchAttempt) {
        when (val retrySchedule = nextDeliveryRetrySchedule(attempt.attempt)) {
            is DeliveryRetrySchedule.RetryAttempt -> {
                IrisLogger.error(
                    "[H2cDispatcher] Delivery retry scheduled: route=${attempt.item.delivery.route}, " +
                        "messageId=${attempt.item.delivery.messageId}, " +
                        "nextAttempt=${retrySchedule.nextAttempt + 1}/$MAX_DELIVERY_ATTEMPTS, " +
                        "delayMs=${retrySchedule.delayMs}",
                )
                if (!scheduleDispatchAttempt(attempt.copy(attempt = retrySchedule.nextAttempt), retrySchedule.delayMs)) {
                    queuedAdmissionKeys.remove(attempt.item.handle.key)
                    IrisLogger.error(
                        "[H2cDispatcher] Failed to schedule retry attempt: " +
                            "route=${attempt.item.delivery.route}, " +
                            "messageId=${attempt.item.delivery.messageId}",
                    )
                }
            }

            is DeliveryRetrySchedule.RestartPass -> {
                IrisLogger.error(
                    "[H2cDispatcher] Exhausted retries: route=${attempt.item.delivery.route}, " +
                        "messageId=${attempt.item.delivery.messageId}",
                )
                IrisLogger.error(
                    "[H2cDispatcher] Delivery deferred for retry: route=${attempt.item.delivery.route}, " +
                        "messageId=${attempt.item.delivery.messageId}, delayMs=${retrySchedule.delayMs}",
                )
                if (!scheduleDispatchAttempt(attempt.copy(attempt = 0), retrySchedule.delayMs)) {
                    queuedAdmissionKeys.remove(attempt.item.handle.key)
                    IrisLogger.error(
                        "[H2cDispatcher] Failed to reschedule deferred delivery: " +
                            "route=${attempt.item.delivery.route}, " +
                            "messageId=${attempt.item.delivery.messageId}",
                    )
                }
            }
        }
    }

    private fun dispatchAttempt(
        delivery: QueuedDelivery,
        attempt: Int,
    ): DeliveryOutcome {
        val body = buildRequestBody(delivery)
        val attemptLabel = "${attempt + 1}/$MAX_DELIVERY_ATTEMPTS"
        return attemptDispatch(delivery, body, attemptLabel)
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
            if (!delivery.threadId.isNullOrBlank()) {
                put("threadId", delivery.threadId)
            }
        }.toString()

    private fun attemptDispatch(
        delivery: QueuedDelivery,
        body: String,
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
                    .post(body.toRequestBody(APPLICATION_JSON.toMediaType()))
                    .header(HEADER_CONTENT_TYPE, APPLICATION_JSON)
                    .header(HEADER_IRIS_MESSAGE_ID, delivery.messageId)
                    .header(HEADER_IRIS_ROUTE, delivery.route)
                    .apply {
                        val webhookToken = Configurable.webhookToken
                        if (webhookToken.isNotBlank()) {
                            header(HEADER_IRIS_TOKEN, webhookToken)
                        }
                    }.build()
            clientFor(delivery.url).newCall(request).execute().use { response ->
                classifyResponse(delivery, attemptLabel, response.code)
            }
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

    private data class DispatchAttempt(
        val item: StoredDelivery,
        val attempt: Int = 0,
    )

    private enum class WebhookTransport {
        H2C,
        HTTP1,
    }

    companion object {
        private const val WORKER_SHUTDOWN_TIMEOUT_MS = 10_000L
        internal const val RETRY_LATER_REQUEUE_DELAY_MS = 5_000L
        private const val QUEUE_FULL_RETRY_DELAY_MS = 250L
        private const val DISPATCH_WORKER_COUNT = 1
        private const val DISPATCH_QUEUE_CAPACITY = 64
        private const val MAX_CONCURRENT_REQUESTS = 8
        private const val MAX_CONCURRENT_REQUESTS_PER_HOST = 4
        private const val MAX_IDLE_CONNECTIONS = 4
        private const val KEEP_ALIVE_DURATION_MS = 30_000L
        internal const val MAX_DELIVERY_ATTEMPTS = 6

        private const val ROUTE_HOLOLIVE = "hololive"

        private const val HEADER_IRIS_TOKEN = "X-Iris-Token"
        private const val HEADER_IRIS_MESSAGE_ID = "X-Iris-Message-Id"
        private const val HEADER_IRIS_ROUTE = "X-Iris-Route"
        private const val HEADER_CONTENT_TYPE = "Content-Type"
        private const val APPLICATION_JSON = "application/json"
        private const val NONE = "<none>"

        private const val OUTBOX_DIR_PATH = "/data/local/tmp/iris-webhook-outbox"

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

internal sealed interface DeliveryRetrySchedule {
    data class RetryAttempt(
        val nextAttempt: Int,
        val delayMs: Long,
    ) : DeliveryRetrySchedule

    data class RestartPass(
        val delayMs: Long,
    ) : DeliveryRetrySchedule
}

internal fun nextDeliveryRetrySchedule(
    attempt: Int,
    maxDeliveryAttempts: Int = H2cDispatcher.MAX_DELIVERY_ATTEMPTS,
    retryLaterDelayMs: Long = H2cDispatcher.RETRY_LATER_REQUEUE_DELAY_MS,
    backoffDelayProvider: (Int) -> Long = ::nextBackoffDelayMs,
): DeliveryRetrySchedule =
    if (attempt < maxDeliveryAttempts - 1) {
        DeliveryRetrySchedule.RetryAttempt(
            nextAttempt = attempt + 1,
            delayMs = backoffDelayProvider(attempt),
        )
    } else {
        DeliveryRetrySchedule.RestartPass(delayMs = retryLaterDelayMs)
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

private sealed interface DurableHandle {
    val key: String

    data class Journal(
        val messageId: String,
    ) : DurableHandle {
        override val key: String = "journal:$messageId"
    }

    data class LegacyFile(
        val file: File,
    ) : DurableHandle {
        override val key: String = "legacy:${file.absolutePath}"
    }
}

private data class StoredDelivery(
    val handle: DurableHandle,
    val delivery: QueuedDelivery,
)

@Serializable
private data class JournalRecord(
    val type: String,
    val messageId: String,
    val delivery: QueuedDelivery? = null,
)

private class DurableAdmissionStore(
    private val outboxDir: File,
    private val json: Json,
) {
    private val pendingJournalDeliveries = linkedMapOf<String, QueuedDelivery>()
    private val journalFile = File(outboxDir, JOURNAL_FILE_NAME)
    private var journalRecordCount = 0

    init {
        ensureOutboxDir()
        loadJournal()
    }

    fun recoverPending(): List<StoredDelivery> =
        synchronized(this) {
            val recovered = ArrayList<StoredDelivery>()
            val seenMessageIds = HashSet<String>()

            recoverLegacyFiles().forEach { storedDelivery ->
                if (seenMessageIds.add(storedDelivery.delivery.messageId)) {
                    recovered.add(storedDelivery)
                }
            }

            pendingJournalDeliveries.values.forEach { delivery ->
                if (seenMessageIds.add(delivery.messageId)) {
                    recovered.add(
                        StoredDelivery(
                            handle = DurableHandle.Journal(delivery.messageId),
                            delivery = delivery,
                        ),
                    )
                }
            }

            recovered
        }

    fun admit(delivery: QueuedDelivery): StoredDelivery =
        synchronized(this) {
            pendingJournalDeliveries[delivery.messageId]?.let { existing ->
                StoredDelivery(DurableHandle.Journal(existing.messageId), existing)
            } ?: run {
                appendJournalRecord(
                    JournalRecord(
                        type = JOURNAL_OP_ADMIT,
                        messageId = delivery.messageId,
                        delivery = delivery,
                    ),
                    sync = true,
                )
                pendingJournalDeliveries[delivery.messageId] = delivery
                StoredDelivery(DurableHandle.Journal(delivery.messageId), delivery)
            }
        }

    fun complete(handle: DurableHandle): Boolean =
        synchronized(this) {
            when (handle) {
                is DurableHandle.LegacyFile -> handle.file.delete()
                is DurableHandle.Journal -> retireJournalAdmission(handle.messageId)
            }
        }

    fun isRecoverable(handle: DurableHandle): Boolean =
        synchronized(this) {
            when (handle) {
                is DurableHandle.LegacyFile -> handle.file.exists()
                is DurableHandle.Journal -> pendingJournalDeliveries.containsKey(handle.messageId)
            }
        }

    private fun retireJournalAdmission(messageId: String): Boolean {
        val delivery = pendingJournalDeliveries[messageId] ?: return true
        val completionRecord =
            JournalRecord(
                type = JOURNAL_OP_COMPLETE,
                messageId = messageId,
            )

        return runCatching {
            pendingJournalDeliveries.remove(messageId)
            if (pendingJournalDeliveries.isEmpty() && journalFile.exists() && journalFile.delete()) {
                journalRecordCount = 0
                true
            } else {
                appendJournalRecord(completionRecord, sync = false)
                compactJournalIfNeeded()
                true
            }
        }.getOrElse { error ->
            pendingJournalDeliveries[messageId] = delivery
            IrisLogger.error(
                "[H2cDispatcher] Failed to retire journal admission: " +
                    "messageId=$messageId: ${error.message}",
            )
            false
        }
    }

    private fun recoverLegacyFiles(): List<StoredDelivery> =
        outboxDir
            .listFiles { file -> file.isFile && file.extension == LEGACY_OUTBOX_FILE_EXTENSION }
            ?.sortedBy { it.name }
            .orEmpty()
            .mapNotNull { file ->
                runCatching {
                    StoredDelivery(
                        handle = DurableHandle.LegacyFile(file),
                        delivery = json.decodeFromString(QueuedDelivery.serializer(), file.readText()),
                    )
                }.getOrElse { error ->
                    IrisLogger.error("[H2cDispatcher] Invalid outbox file ${file.name}: ${error.message}")
                    file.delete()
                    null
                }
            }

    private fun loadJournal() {
        if (!journalFile.exists()) {
            return
        }

        journalFile.forEachLine { line ->
            if (line.isBlank()) {
                return@forEachLine
            }

            val record =
                runCatching {
                    json.decodeFromString(JournalRecord.serializer(), line)
                }.getOrElse { error ->
                    IrisLogger.error("[H2cDispatcher] Invalid journal entry: ${error.message}")
                    return@forEachLine
                }

            when (record.type) {
                JOURNAL_OP_ADMIT -> {
                    val delivery = record.delivery
                    if (delivery == null) {
                        IrisLogger.error("[H2cDispatcher] Journal admit entry missing delivery: ${record.messageId}")
                        return@forEachLine
                    }
                    pendingJournalDeliveries[delivery.messageId] = delivery
                    journalRecordCount++
                }

                JOURNAL_OP_COMPLETE -> {
                    pendingJournalDeliveries.remove(record.messageId)
                    journalRecordCount++
                }
                else -> IrisLogger.error("[H2cDispatcher] Unknown journal entry type: ${record.type}")
            }
        }
    }

    private fun appendJournalRecord(
        record: JournalRecord,
        sync: Boolean,
    ) {
        ensureOutboxDir()
        val payload = json.encodeToString(JournalRecord.serializer(), record) + "\n"
        FileOutputStream(journalFile, true).use { output ->
            output.write(payload.toByteArray(Charsets.UTF_8))
            if (sync) {
                output.fd.sync()
            }
        }
        journalRecordCount++
    }

    private fun ensureOutboxDir() {
        check(outboxDir.mkdirs() || outboxDir.exists()) {
            "Failed to create outbox directory: ${outboxDir.absolutePath}"
        }
    }

    private fun compactJournalIfNeeded() {
        if (journalRecordCount < JOURNAL_COMPACTION_RECORD_THRESHOLD) {
            return
        }
        if (pendingJournalDeliveries.size * JOURNAL_COMPACTION_PENDING_RATIO_DENOMINATOR > journalRecordCount) {
            return
        }

        val tempFile = File(outboxDir, "$JOURNAL_FILE_NAME.tmp")
        runCatching {
            FileOutputStream(tempFile, false).use { output ->
                pendingJournalDeliveries.values.forEach { delivery ->
                    val payload =
                        json.encodeToString(
                            JournalRecord.serializer(),
                            JournalRecord(
                                type = JOURNAL_OP_ADMIT,
                                messageId = delivery.messageId,
                                delivery = delivery,
                            ),
                        ) + "\n"
                    output.write(payload.toByteArray(Charsets.UTF_8))
                }
                output.fd.sync()
            }
            check(tempFile.renameTo(journalFile)) {
                tempFile.delete()
                "Failed to promote compacted journal file"
            }
            journalRecordCount = pendingJournalDeliveries.size
        }.onFailure { error ->
            tempFile.delete()
            IrisLogger.error("[H2cDispatcher] Journal compaction failed: ${error.message}")
        }
    }

    companion object {
        private const val JOURNAL_FILE_NAME = "queue.log"
        private const val LEGACY_OUTBOX_FILE_EXTENSION = "json"
        private const val JOURNAL_OP_ADMIT = "admit"
        private const val JOURNAL_OP_COMPLETE = "complete"
        private const val JOURNAL_COMPACTION_RECORD_THRESHOLD = 128
        private const val JOURNAL_COMPACTION_PENDING_RATIO_DENOMINATOR = 2
    }
}
