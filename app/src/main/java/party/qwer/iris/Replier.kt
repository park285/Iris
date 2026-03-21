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
import party.qwer.iris.Replier.Companion.SendMessageRequest
import java.io.File

// SendMsg : ye-seola/go-kdb

class Replier {
    companion object {
        private const val MESSAGE_CHANNEL_CAPACITY = 64
        private const val LOG_MESSAGE_PREVIEW_LENGTH = 30
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
                IrisLogger.error("[Replier] Cannot start message sender after shutdown")
                return
            }
            if (messageSenderJob?.isActive == true) {
                IrisLogger.debug("[Replier] messageSenderJob is already running")
                return
            }

            messageSenderJob =
                coroutineScope.launch {
                    IrisLogger.debug("[Replier] messageSenderJob started, waiting for messages...")
                    for (request in messageChannel) {
                        try {
                            IrisLogger.debug("[Replier] Processing message from channel")
                            request.send()
                            IrisLogger.debug("[Replier] Message sent successfully")
                            delay(Configurable.messageSendRate)
                        } catch (e: Exception) {
                            IrisLogger.error("[Replier] Error sending message from channel: ${e.message}", e)
                        }
                    }
                    IrisLogger.debug("[Replier] messageSenderJob terminated (channel closed)")
                }
            IrisLogger.debug("[Replier] messageSenderJob launched")
        }

        @OptIn(DelicateCoroutinesApi::class)
        fun restartMessageSender() {
            val jobToCancel = synchronized(this@Companion) {
                if (messageChannel.isClosedForSend) {
                    IrisLogger.error("[Replier] Cannot restart message sender after shutdown")
                    return
                }
                val captured = messageSenderJob
                messageSenderJob = null
                captured
            }

            jobToCancel?.let { runBlocking { it.cancelAndJoin() } }
            startMessageSender()
        }

        /**
         * 종료 시 채널을 닫고 대기 중인 메시지 처리를 기다림.
         */
        fun shutdown() {
            IrisLogger.info("[Replier] Shutting down message channel...")
            val jobToJoin = synchronized(this@Companion) {
                messageChannel.close()
                val captured = messageSenderJob
                messageSenderJob = null
                captured
            }

            jobToJoin?.let { runBlocking { it.join() } }
            IrisLogger.info("[Replier] Message channel closed")
        }

        private val zeroWidthCharacters = setOf('\u200B', '\u200C', '\u200D', '\u2060', '\uFEFF')
        private const val zeroWidthNoBreakSpace = "\uFEFF"

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
            IrisLogger.debugLazy { "[Replier] sendMessageInternal: preparing intent for chatId=$chatId" }
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
                IrisLogger.debug("[Replier] Calling AndroidHiddenApi.startService...")
                AndroidHiddenApi.startService(intent)
                IrisLogger.debug("[Replier] AndroidHiddenApi.startService returned successfully")
            } catch (e: Exception) {
                IrisLogger.error("[Replier] AndroidHiddenApi.startService failed: ${e.message}", e)
                throw e
            }
        }

        fun sendMessage(
            referer: String,
            chatId: Long,
            msg: String,
            threadId: Long?,
            threadScope: Int?,
        ): ReplyAdmissionResult {
            IrisLogger.debugLazy { "[Replier] sendMessage called: chatId=$chatId, msg='${msg.take(LOG_MESSAGE_PREVIEW_LENGTH)}...'" }
            return enqueueRequest(
                SendMessageRequest {
                    IrisLogger.debugLazy { "[Replier] sendMessageInternal executing for chatId=$chatId" }
                    sendMessageInternal(
                        referer,
                        chatId,
                        msg,
                        threadId,
                        threadScope,
                    )
                    IrisLogger.debugLazy { "[Replier] sendMessageInternal completed for chatId=$chatId" }
                },
            )
        }

        fun sendPhoto(
            room: Long,
            base64ImageDataString: String,
        ): ReplyAdmissionResult = sendMultiplePhotos(room, listOf(base64ImageDataString))

        fun sendMultiplePhotos(
            room: Long,
            base64ImageDataStrings: List<String>,
        ): ReplyAdmissionResult {
            if (!isValidBase64ImagePayloads(base64ImageDataStrings)) {
                return ReplyAdmissionResult(
                    ReplyAdmissionStatus.INVALID_PAYLOAD,
                    "image replies require valid base64 payload",
                )
            }
            return enqueueRequest(
                SendMessageRequest {
                    sendImages(
                        room = room,
                        base64ImageDataStrings = base64ImageDataStrings,
                    )
                },
            )
        }

        @Synchronized
        private fun enqueueRequest(request: SendMessageRequest): ReplyAdmissionResult {
            if (messageSenderJob?.isActive != true) {
                IrisLogger.error("[Replier] Rejecting enqueue because sender is unavailable")
                return ReplyAdmissionResult(
                    ReplyAdmissionStatus.SHUTDOWN,
                    "reply sender unavailable",
                )
            }

            val sendResult = messageChannel.trySend(request)
            return when {
                sendResult.isSuccess -> {
                    IrisLogger.debug("[Replier] Message queued to channel successfully")
                    ReplyAdmissionResult(ReplyAdmissionStatus.ACCEPTED)
                }
                sendResult.isClosed -> {
                    IrisLogger.error("[Replier] Rejecting enqueue because channel is closed")
                    ReplyAdmissionResult(
                        ReplyAdmissionStatus.SHUTDOWN,
                        "reply sender unavailable",
                    )
                }
                else -> {
                    IrisLogger.error("[Replier] Rejecting enqueue because queue is full")
                    ReplyAdmissionResult(
                        ReplyAdmissionStatus.QUEUE_FULL,
                        "reply queue is full",
                    )
                }
            }
        }

        private fun sendImages(
            room: Long,
            base64ImageDataStrings: List<String>,
        ) {
            val preparedImages = prepareImages(room, base64ImageDataStrings)
            sendPreparedImages(preparedImages)
        }

        private fun prepareImages(
            room: Long,
            base64ImageDataStrings: List<String>,
        ): PreparedImages =
            try {
                prepareImagesInternal(room, base64ImageDataStrings)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("invalid image payload for room=$room: ${e.message}", e)
            } catch (e: Exception) {
                IrisLogger.error("Error preparing images for room=$room: ${e.message}", e)
                throw e
            }

        private fun prepareImagesInternal(
            room: Long,
            base64ImageDataStrings: List<String>,
        ): PreparedImages {
            require(base64ImageDataStrings.isNotEmpty()) { "no image data provided" }
            ensureImageDir(imageDir)

            val uris = ArrayList<Uri>(base64ImageDataStrings.size)
            val createdFiles = ArrayList<File>(base64ImageDataStrings.size)
            try {
                base64ImageDataStrings.forEach { base64ImageDataString ->
                    val decodedImage = decodeBase64Image(base64ImageDataString)
                    val imageFile = saveImage(decodedImage, imageDir)

                    createdFiles.add(imageFile)
                    val imageUri = Uri.fromFile(imageFile)
                    if (imageMediaScanEnabled) {
                        mediaScan(imageUri)
                    }
                    uris.add(imageUri)
                }

                require(uris.isNotEmpty()) { "no image URIs created" }
                return PreparedImages(
                    room = room,
                    uris = uris,
                    files = createdFiles,
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

        internal fun interface SendMessageRequest {
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

