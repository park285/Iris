package party.qwer.iris

import android.app.RemoteInput
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.SpannableStringBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap

internal data class ReplyQueueKey(val chatId: Long, val threadId: Long?)

// SendMsg : ye-seola/go-kdb

class ReplyService(
    private val config: ConfigProvider,
) : MessageSender {
    private companion object {
        private const val PER_WORKER_QUEUE_CAPACITY = 16
        private const val MAX_WORKERS = 16
        private const val WORKER_IDLE_TIMEOUT_MS = 60_000L
        private const val SHUTDOWN_TIMEOUT_MS = 10_000L
        private const val LOG_MESSAGE_PREVIEW_LENGTH = 30
        private val zeroWidthCharacters = setOf('\u200B', '\u200C', '\u200D', '\u2060', '\uFEFF')
        private const val zeroWidthNoBreakSpace = "\uFEFF"
        private const val THREAD_HINT_PATH = "/data/local/tmp/iris-thread-hint.json"
    }

    private val workerRegistry = ConcurrentHashMap<ReplyQueueKey, ReplyWorkerState>()
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sendMutex = Mutex()
    private var started = false
    private var shutdownComplete = false
    private val imageDir = File(IRIS_IMAGE_DIR_PATH)
    private val imageMediaScanEnabled =
        System
            .getenv("IRIS_IMAGE_MEDIA_SCAN")
            ?.trim()
            ?.lowercase()
            ?.let { raw -> raw != "0" && raw != "false" && raw != "off" }
            ?: true

    @Synchronized
    fun start() {
        if (shutdownComplete) {
            IrisLogger.error("[ReplyService] Cannot start after shutdown")
            return
        }
        started = true
        IrisLogger.debug("[ReplyService] started (workers created on demand)")
    }

    fun restart() {
        IrisLogger.info("[ReplyService] Restarting...")
        val snapshot: List<ReplyWorkerState>
        synchronized(this) {
            if (shutdownComplete) {
                IrisLogger.error("[ReplyService] Cannot restart after shutdown")
                return
            }
            started = false
            snapshot = workerRegistry.values.toList()
        }
        snapshot.forEach { it.channel.close() }
        runBlocking {
            snapshot.forEach { worker ->
                withTimeoutOrNull(SHUTDOWN_TIMEOUT_MS) { worker.job.join() } ?: run {
                    worker.job.cancel()
                    withTimeoutOrNull(SHUTDOWN_TIMEOUT_MS) { worker.job.join() }
                        ?: IrisLogger.error("[ReplyService] Worker(${worker.key}) cancel timed out; abandoning")
                }
            }
        }
        synchronized(this) {
            workerRegistry.clear()
            started = true
        }
        IrisLogger.info("[ReplyService] Restart complete")
    }

    fun shutdown() {
        IrisLogger.info("[ReplyService] Shutting down...")
        synchronized(this) {
            started = false
            shutdownComplete = true
        }
        val workers = workerRegistry.values.toList()
        workers.forEach { it.channel.close() }
        runBlocking {
            workers.forEach { worker ->
                withTimeoutOrNull(SHUTDOWN_TIMEOUT_MS) { worker.job.join() } ?: run {
                    worker.job.cancel()
                    withTimeoutOrNull(SHUTDOWN_TIMEOUT_MS) { worker.job.join() }
                        ?: IrisLogger.error("[ReplyService] Worker(${worker.key}) cancel timed out; abandoning")
                }
            }
        }
        workerRegistry.clear()
        IrisLogger.info("[ReplyService] Shutdown complete")
    }

    private fun preserveInvisiblePadding(message: String): CharSequence {
        if (message.isEmpty()) {
            return message
        }

        var containsZeroWidth = false
        for (ch in message) {
            if (ch in zeroWidthCharacters) {
                containsZeroWidth = true
                break
            }
        }

        if (!containsZeroWidth) {
            return message
        }

        val builder = SpannableStringBuilder()
        message.forEach { ch ->
            builder.append(ch.toString())
            if (ch == '\u200B') {
                builder.append(zeroWidthNoBreakSpace)
            }
        }
        return builder
    }

    private fun sendMessageInternal(
        referer: String,
        chatId: Long,
        msg: String,
        threadId: Long?,
        threadScope: Int?,
    ) {
        IrisLogger.debugLazy { "[ReplyService] sendMessageInternal: preparing intent for chatId=$chatId" }
        val preparedMessage = preserveInvisiblePadding(msg)
        val isThreadNotification = threadId != null || threadScope != null

        val intent =
            Intent().apply {
                component =
                    ComponentName(
                        "com.kakao.talk",
                        "com.kakao.talk.notification.NotificationActionService",
                    )
                putExtra("noti_referer", referer)
                putExtra("chat_id", chatId)

                putExtra("is_chat_thread_notification", isThreadNotification)
                if (threadId != null) {
                    putExtra("thread_id", threadId)
                }
                if (threadScope != null) {
                    putExtra("scope", threadScope)
                }

                action = "com.kakao.talk.notification.REPLY_MESSAGE"

                val results =
                    Bundle().apply {
                        putCharSequence("reply_message", preparedMessage)
                    }

                val remoteInput = RemoteInput.Builder("reply_message").build()
                RemoteInput.addResultsToIntent(arrayOf(remoteInput), this, results)
            }

        try {
            IrisLogger.debug("[ReplyService] Calling AndroidHiddenApi.startService...")
            AndroidHiddenApi.startService(intent)
            IrisLogger.debug("[ReplyService] AndroidHiddenApi.startService returned successfully")
        } catch (e: Exception) {
            IrisLogger.error("[ReplyService] AndroidHiddenApi.startService failed: ${e.message}", e)
            throw e
        }
    }

    override fun sendMessage(
        referer: String,
        chatId: Long,
        msg: String,
        threadId: Long?,
        threadScope: Int?,
    ): ReplyAdmissionResult {
        IrisLogger.debugLazy { "[ReplyService] sendMessage called: chatId=$chatId, msg='${msg.take(LOG_MESSAGE_PREVIEW_LENGTH)}...'" }
        return enqueueRequest(
            chatId,
            threadId,
            object : SendMessageRequest {
                override suspend fun send() {
                    sendMessageInternal(referer, chatId, msg, threadId, threadScope)
                }
            },
        )
    }

    override fun sendPhoto(
        room: Long,
        base64ImageDataString: String,
        threadId: Long?,
        threadScope: Int?,
    ): ReplyAdmissionResult = sendMultiplePhotos(room, listOf(base64ImageDataString), threadId, threadScope)

    override fun sendMultiplePhotos(
        room: Long,
        base64ImageDataStrings: List<String>,
        threadId: Long?,
        threadScope: Int?,
    ): ReplyAdmissionResult {
        val decodedImages =
            try {
                require(base64ImageDataStrings.isNotEmpty()) { "no image data provided" }
                base64ImageDataStrings.map { base64 ->
                    require(base64.length <= MAX_BASE64_IMAGE_PAYLOAD_LENGTH) { "payload exceeds size limit" }
                    decodeBase64Image(base64)
                }
            } catch (_: IllegalArgumentException) {
                return ReplyAdmissionResult(
                    ReplyAdmissionStatus.INVALID_PAYLOAD,
                    "image replies require valid base64 payload",
                )
            }

        return enqueueRequest(
            room,
            threadId,
            object : SendMessageRequest {
                private lateinit var preparedImages: PreparedImages

                override suspend fun prepare() {
                    ensureImageDir(imageDir)
                    val uris = ArrayList<Uri>(decodedImages.size)
                    val createdFiles = ArrayList<File>(decodedImages.size)
                    try {
                        decodedImages.forEach { imageBytes ->
                            val imageFile = saveImage(imageBytes, imageDir)
                            createdFiles.add(imageFile)
                            val imageUri = Uri.fromFile(imageFile)
                            if (imageMediaScanEnabled) {
                                mediaScan(imageUri)
                            }
                            uris.add(imageUri)
                        }
                        require(uris.isNotEmpty()) { "no image URIs created" }
                    } catch (e: Exception) {
                        createdFiles.forEach { file ->
                            if (file.exists() && !file.delete()) {
                                IrisLogger.error("Failed to delete partially prepared image file: ${file.absolutePath}")
                            }
                        }
                        throw e
                    }
                    preparedImages = PreparedImages(room = room, uris = uris, files = createdFiles)
                }

                override suspend fun send() {
                    writeThreadHint(room, threadId, threadScope)
                    sendPreparedImages(preparedImages)
                }
            },
        )
    }

    internal fun enqueueAction(
        chatId: Long,
        threadId: Long?,
        action: suspend () -> Unit,
    ): ReplyAdmissionResult =
        enqueueRequest(
            chatId,
            threadId,
            object : SendMessageRequest {
                override suspend fun send() = action()
            },
        )

    override fun sendTextShare(
        room: Long,
        msg: String,
    ): ReplyAdmissionResult =
        enqueueRequest(
            room,
            null,
            object : SendMessageRequest {
                override suspend fun send() {
                    sendTextShareInternal(room, msg)
                }
            },
        )

    override fun sendReplyMarkdown(
        room: Long,
        msg: String,
    ): ReplyAdmissionResult =
        enqueueRequest(
            room,
            null,
            object : SendMessageRequest {
                override suspend fun send() {
                    sendReplyMarkdownInternal(room, msg)
                }
            },
        )

    private fun launchWorker(key: ReplyQueueKey): ReplyWorkerState {
        val channel = Channel<SendMessageRequest>(PER_WORKER_QUEUE_CAPACITY)
        lateinit var state: ReplyWorkerState
        val job =
            coroutineScope.launch {
                var idleTimeout = false
                try {
                    while (true) {
                        val request =
                            withTimeoutOrNull(WORKER_IDLE_TIMEOUT_MS) {
                                channel.receive()
                            }
                        if (request == null) {
                            idleTimeout = true
                            break
                        }

                        try {
                            request.prepare()
                            sendMutex.withLock {
                                request.send()
                            }
                            delay(config.messageSendRate)
                        } catch (e: Exception) {
                            IrisLogger.error("[ReplyService] worker($key) send error: ${e.message}", e)
                        }
                    }
                } finally {
                    channel.close()
                    workerRegistry.remove(key, state)
                    val reason = if (idleTimeout) "idle timeout" else "channel closed"
                    IrisLogger.debug("[ReplyService] worker($key) terminated ($reason)")
                }
            }
        state = ReplyWorkerState(key, channel, job)
        return state
    }

    private fun getOrCreateWorker(key: ReplyQueueKey): ReplyWorkerState? {
        workerRegistry[key]?.let { return it }

        synchronized(this) {
            workerRegistry[key]?.let { return it }
            if (workerRegistry.size >= MAX_WORKERS) {
                return null
            }
            val worker = launchWorker(key)
            workerRegistry[key] = worker
            return worker
        }
    }

    @Synchronized
    private fun enqueueRequest(
        chatId: Long,
        threadId: Long?,
        request: SendMessageRequest,
    ): ReplyAdmissionResult {
        if (!started) {
            IrisLogger.error("[ReplyService] Rejecting enqueue because sender is unavailable")
            return ReplyAdmissionResult(ReplyAdmissionStatus.SHUTDOWN, "reply sender unavailable")
        }

        val key = ReplyQueueKey(chatId, threadId)
        val worker =
            getOrCreateWorker(key)
                ?: return ReplyAdmissionResult(ReplyAdmissionStatus.QUEUE_FULL, "too many active reply workers")

        val sendResult = worker.channel.trySend(request)
        return when {
            sendResult.isSuccess -> {
                IrisLogger.debug("[ReplyService] Message queued to worker($key)")
                ReplyAdmissionResult(ReplyAdmissionStatus.ACCEPTED)
            }
            sendResult.isClosed -> {
                workerRegistry.remove(key, worker)
                val retryWorker =
                    getOrCreateWorker(key)
                        ?: return ReplyAdmissionResult(ReplyAdmissionStatus.QUEUE_FULL, "too many active reply workers")
                val retryResult = retryWorker.channel.trySend(request)
                if (retryResult.isSuccess) {
                    ReplyAdmissionResult(ReplyAdmissionStatus.ACCEPTED)
                } else {
                    ReplyAdmissionResult(ReplyAdmissionStatus.QUEUE_FULL, "reply queue is full")
                }
            }
            else -> {
                IrisLogger.error("[ReplyService] Rejecting enqueue because queue is full for worker($key)")
                ReplyAdmissionResult(ReplyAdmissionStatus.QUEUE_FULL, "reply queue is full")
            }
        }
    }

    private fun writeThreadHint(
        room: Long,
        threadId: Long?,
        threadScope: Int?,
    ) {
        val hintFile = java.io.File(THREAD_HINT_PATH)
        if (threadId != null && threadScope != null && threadScope >= 2) {
            hintFile.writeText("{\"room\":\"$room\",\"threadId\":\"$threadId\",\"threadScope\":$threadScope}")
            IrisLogger.debug("[ReplyService] thread hint written: room=$room threadId=$threadId scope=$threadScope")
        } else {
            if (hintFile.exists()) hintFile.delete()
        }
    }

    private fun sendPreparedImages(preparedImages: PreparedImages) {
        val intent =
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                setPackage("com.kakao.talk")
                type = "image/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, preparedImages.uris)
                putExtra("key_id", preparedImages.room)
                putExtra("key_type", 1)
                putExtra("key_from_direct_share", true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }

        try {
            AndroidHiddenApi.startActivity(intent)
        } catch (e: Exception) {
            IrisLogger.error("Error starting activity for sending multiple photos: $e")
            cleanupPreparedImages(preparedImages)
            throw e
        }
    }

    private fun sendTextShareInternal(
        room: Long,
        msg: String,
    ) {
        val preparedMessage = preserveInvisiblePadding(msg)
        val spec = buildReplyMarkdownIntentSpec(room, preparedMessage)
        val intent = buildReplyMarkdownIntent(room, preparedMessage)

        try {
            AndroidHiddenApi.startActivityAs(spec.callerPackageName, intent)
        } catch (e: Exception) {
            IrisLogger.error("Error starting activity for sending text share: $e")
            throw e
        }
    }

    private fun sendReplyMarkdownInternal(
        room: Long,
        msg: String,
    ) {
        val preparedMessage = preserveInvisiblePadding(msg)
        val spec = buildReplyMarkdownIntentSpec(room, preparedMessage)
        val intent = buildReplyMarkdownIntent(room, preparedMessage)

        try {
            AndroidHiddenApi.startActivityAs(spec.callerPackageName, intent)
        } catch (e: Exception) {
            IrisLogger.error("Error starting activity for reply-markdown: $e")
            throw e
        }
    }

    private data class ReplyWorkerState(
        val key: ReplyQueueKey,
        val channel: Channel<SendMessageRequest>,
        val job: Job,
    )

    private interface SendMessageRequest {
        suspend fun prepare() {}

        suspend fun send()
    }

    // Android Q+ deprecated ACTION_MEDIA_SCANNER_SCAN_FILE
    // Context 없는 환경이므로 MediaScannerConnection 사용 불가
    // shell context에서 broadcast 방식 유지
    @Suppress("DEPRECATION")
    private fun mediaScan(uri: Uri) {
        val mediaScanIntent =
            Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE).apply {
                data = uri
            }
        AndroidHiddenApi.broadcastIntent(mediaScanIntent)
    }
}

private data class PreparedImages(
    val room: Long,
    val uris: ArrayList<Uri>,
    val files: ArrayList<File>,
)

private fun ensureImageDir(imageDir: File) {
    if (imageDir.exists()) {
        return
    }
    check(imageDir.mkdirs() || imageDir.exists()) {
        "Failed to create image directory: ${imageDir.absolutePath}"
    }
}

private fun cleanupPreparedImages(preparedImages: PreparedImages) {
    preparedImages.files.forEach { file ->
        if (file.exists() && !file.delete()) {
            IrisLogger.error("Failed to delete prepared image file: ${file.absolutePath}")
        }
    }
}
