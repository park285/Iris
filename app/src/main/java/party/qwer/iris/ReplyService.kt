package party.qwer.iris

import android.app.RemoteInput
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.SpannableStringBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

// SendMsg : ye-seola/go-kdb

class ReplyService(
    private val config: ConfigProvider,
) : MessageSender {
    private companion object {
        private const val MESSAGE_CHANNEL_CAPACITY = 64
        private const val LOG_MESSAGE_PREVIEW_LENGTH = 30
        private const val SHUTDOWN_TIMEOUT_MS = 10_000L
        private val zeroWidthCharacters = setOf('\u200B', '\u200C', '\u200D', '\u2060', '\uFEFF')
        private const val zeroWidthNoBreakSpace = "\uFEFF"
        private const val THREAD_HINT_PATH = "/data/local/tmp/iris-thread-hint.json"
    }

    private val messageChannel = Channel<SendMessageRequest>(capacity = MESSAGE_CHANNEL_CAPACITY)
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var messageSenderJob: Job? = null
    private val imageDir = File(IRIS_IMAGE_DIR_PATH)
    private val imageMediaScanEnabled =
        System
            .getenv("IRIS_IMAGE_MEDIA_SCAN")
            ?.trim()
            ?.lowercase()
            ?.let { raw -> raw != "0" && raw != "false" && raw != "off" }
            ?: true

    @OptIn(DelicateCoroutinesApi::class)
    @Synchronized
    fun startMessageSender() {
        if (messageChannel.isClosedForSend) {
            IrisLogger.error("[ReplyService] Cannot start message sender after shutdown")
            return
        }
        if (messageSenderJob?.isActive == true) {
            IrisLogger.debug("[ReplyService] messageSenderJob is already running")
            return
        }

        messageSenderJob =
            coroutineScope.launch {
                IrisLogger.debug("[ReplyService] messageSenderJob started, waiting for messages...")
                for (request in messageChannel) {
                    try {
                        IrisLogger.debug("[ReplyService] Processing message from channel")
                        request.send()
                        IrisLogger.debug("[ReplyService] Message sent successfully")
                        delay(config.messageSendRate)
                    } catch (e: Exception) {
                        IrisLogger.error("[ReplyService] Error sending message from channel: ${e.message}", e)
                    }
                }
                IrisLogger.debug("[ReplyService] messageSenderJob terminated (channel closed)")
            }
        IrisLogger.debug("[ReplyService] messageSenderJob launched")
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Synchronized
    fun restartMessageSender() {
        if (messageChannel.isClosedForSend) {
            IrisLogger.error("[ReplyService] Cannot restart message sender after shutdown")
            return
        }
        val jobToCancel = messageSenderJob
        messageSenderJob = null

        jobToCancel?.let { runBlocking { it.cancelAndJoin() } }
        startMessageSender()
    }

    /**
     * 종료 시 채널을 닫고 대기 중인 메시지 처리를 기다림.
     */
    fun shutdown() {
        IrisLogger.info("[ReplyService] Shutting down message channel...")
        val jobToJoin =
            synchronized(this) {
                messageChannel.close()
                val captured = messageSenderJob
                messageSenderJob = null
                captured
            }

        jobToJoin?.let {
            runBlocking {
                val completed =
                    withTimeoutOrNull(SHUTDOWN_TIMEOUT_MS) {
                        it.join()
                        true
                    } == true
                if (!completed) {
                    IrisLogger.error("[ReplyService] Message sender shutdown timed out; cancelling")
                    withTimeoutOrNull(SHUTDOWN_TIMEOUT_MS) {
                        it.cancelAndJoin()
                    } ?: IrisLogger.error("[ReplyService] Message sender cancel timed out; abandoning")
                }
            }
        }
        IrisLogger.info("[ReplyService] Message channel closed")
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
            SendMessageRequest {
                IrisLogger.debugLazy { "[ReplyService] sendMessageInternal executing for chatId=$chatId" }
                sendMessageInternal(
                    referer,
                    chatId,
                    msg,
                    threadId,
                    threadScope,
                )
                IrisLogger.debugLazy { "[ReplyService] sendMessageInternal completed for chatId=$chatId" }
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
            SendMessageRequest {
                writeThreadHint(room, threadId, threadScope)
                sendDecodedImages(room, decodedImages)
            },
        )
    }

    override fun sendTextShare(
        room: Long,
        msg: String,
    ): ReplyAdmissionResult =
        enqueueRequest(
            SendMessageRequest {
                sendTextShareInternal(room, msg)
            },
        )

    override fun sendThreadMarkdown(
        room: Long,
        msg: String,
        threadId: Long,
        threadScope: Int,
    ): ReplyAdmissionResult =
        try {
            val before = DataStoreWatcher.capture()
            IrisLogger.info(DataStoreWatcher.formatSnapshot("before_thread_markdown", before))
            sendThreadMarkdownInternal(room, msg, threadId, threadScope)
            val after = DataStoreWatcher.capture()
            IrisLogger.info(DataStoreWatcher.formatSnapshot("after_thread_markdown", after))
            val changes = DataStoreWatcher.diff(before, after)
            if (changes.isNotEmpty()) {
                IrisLogger.error("[DataStoreWatcher] DATASTORE CHANGED: ${changes.joinToString("; ")}")
            }
            ReplyAdmissionResult(ReplyAdmissionStatus.ACCEPTED)
        } catch (e: Exception) {
            ReplyAdmissionResult(
                ReplyAdmissionStatus.INVALID_PAYLOAD,
                "${e.javaClass.name}: ${e.message ?: "thread markdown send failed"} (see /data/local/tmp/iris-thread-markdown-debug.txt)",
            )
        }

    override fun sendThreadTextShare(
        room: Long,
        msg: String,
        threadId: Long,
        threadScope: Int,
    ): ReplyAdmissionResult =
        enqueueRequest(
            SendMessageRequest {
                sendThreadTextShareInternal(room, msg, threadId, threadScope)
            },
        )

    @Synchronized
    private fun enqueueRequest(request: SendMessageRequest): ReplyAdmissionResult {
        if (messageSenderJob?.isActive != true) {
            IrisLogger.error("[ReplyService] Rejecting enqueue because sender is unavailable")
            return ReplyAdmissionResult(
                ReplyAdmissionStatus.SHUTDOWN,
                "reply sender unavailable",
            )
        }

        val sendResult = messageChannel.trySend(request)
        return when {
            sendResult.isSuccess -> {
                IrisLogger.debug("[ReplyService] Message queued to channel successfully")
                ReplyAdmissionResult(ReplyAdmissionStatus.ACCEPTED)
            }
            sendResult.isClosed -> {
                IrisLogger.error("[ReplyService] Rejecting enqueue because channel is closed")
                ReplyAdmissionResult(
                    ReplyAdmissionStatus.SHUTDOWN,
                    "reply sender unavailable",
                )
            }
            else -> {
                IrisLogger.error("[ReplyService] Rejecting enqueue because queue is full")
                ReplyAdmissionResult(
                    ReplyAdmissionStatus.QUEUE_FULL,
                    "reply queue is full",
                )
            }
        }
    }

    private fun sendDecodedImages(
        room: Long,
        decodedImages: List<ByteArray>,
    ) {
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
            sendPreparedImages(
                PreparedImages(
                    room = room,
                    uris = uris,
                    files = createdFiles,
                ),
            )
        } catch (e: Exception) {
            createdFiles.forEach { file ->
                if (file.exists() && !file.delete()) {
                    IrisLogger.error("Failed to delete partially prepared image file: ${file.absolutePath}")
                }
            }
            throw e
        }
    }

    private fun writeThreadHint(room: Long, threadId: Long?, threadScope: Int?) {
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
        val spec = buildTextShareIntentSpec(room, preparedMessage)
        val intent = buildTextShareIntent(room, preparedMessage)

        try {
            AndroidHiddenApi.startActivityAs(spec.callerPackageName, intent)
        } catch (e: Exception) {
            IrisLogger.error("Error starting activity for sending text share: $e")
            throw e
        }
    }

    private fun sendThreadTextShareInternal(
        room: Long,
        msg: String,
        threadId: Long,
        threadScope: Int,
    ) {
        val preparedMessage = preserveInvisiblePadding(msg)
        val intent = buildThreadTextShareIntent(room, preparedMessage, threadId, threadScope)
        val before = DataStoreWatcher.capture()
        IrisLogger.info(DataStoreWatcher.formatSnapshot("before_thread_text_share", before))

        try {
            AndroidHiddenApi.startActivityAs("com.kakao.talk", intent)
        } catch (e: Exception) {
            IrisLogger.error("Error starting activity for thread text share: $e")
            throw e
        } finally {
            val after = DataStoreWatcher.capture()
            IrisLogger.info(DataStoreWatcher.formatSnapshot("after_thread_text_share", after))
            val changes = DataStoreWatcher.diff(before, after)
            if (changes.isNotEmpty()) {
                IrisLogger.error("[DataStoreWatcher] DATASTORE CHANGED: ${changes.joinToString("; ")}")
            }
        }
    }

    private fun sendThreadMarkdownInternal(
        room: Long,
        msg: String,
        threadId: Long,
        threadScope: Int,
    ) {
        val preparedMessage = preserveInvisiblePadding(msg)

        try {
            KakaoThreadMarkdownSender.send(
                room = room,
                threadId = threadId,
                threadScope = threadScope,
                text = preparedMessage,
            )
        } catch (e: Exception) {
            IrisLogger.error("Error calling Kakao thread markdown sender: $e")
            throw e
        }
    }

    private fun interface SendMessageRequest {
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
