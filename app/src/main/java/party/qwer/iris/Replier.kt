package party.qwer.iris

import android.app.RemoteInput
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import party.qwer.iris.Replier.Companion.SendMessageRequest
import java.io.File

// SendMsg : ye-seola/go-kdb

class Replier {
    companion object {
        private const val MESSAGE_CHANNEL_CAPACITY = 64
        private const val LOG_MESSAGE_PREVIEW_LENGTH = 30
        private val messageChannel = Channel<SendMessageRequest>(capacity = MESSAGE_CHANNEL_CAPACITY)
        private val coroutineScope = CoroutineScope(Dispatchers.IO)
        private var messageSenderJob: Job? = null
        private val mutex = Mutex()

        init {
            startMessageSender()
        }

        fun startMessageSender() {
            coroutineScope.launch {
                IrisLogger.debug("[Replier] startMessageSender called")
                if (messageSenderJob?.isActive == true) {
                    IrisLogger.debug("[Replier] Cancelling existing messageSenderJob")
                    messageSenderJob?.cancelAndJoin()
                }
                messageSenderJob =
                    launch {
                        IrisLogger.debug("[Replier] messageSenderJob started, waiting for messages...")
                        for (request in messageChannel) {
                            try {
                                IrisLogger.debug("[Replier] Processing message from channel")
                                mutex.withLock {
                                    IrisLogger.debug("[Replier] Sending message...")
                                    request.send()
                                    IrisLogger.debug("[Replier] Message sent successfully")
                                    delay(Configurable.messageSendRate)
                                }
                            } catch (e: Exception) {
                                IrisLogger.error("[Replier] Error sending message from channel: $e")
                                e.printStackTrace()
                            }
                        }
                        IrisLogger.debug("[Replier] messageSenderJob terminated (channel closed)")
                    }
                IrisLogger.debug("[Replier] messageSenderJob launched")
            }
        }

        fun restartMessageSender() {
            startMessageSender()
        }

        /**
         * 종료 시 채널을 닫고 대기 중인 메시지 처리를 기다림.
         */
        fun shutdown() {
            IrisLogger.info("[Replier] Shutting down message channel...")
            messageChannel.close()
            messageSenderJob?.let { job ->
                kotlinx.coroutines.runBlocking {
                    job.join()
                }
            }
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
        ) {
            IrisLogger.debugLazy { "[Replier] sendMessageInternal: preparing intent for chatId=$chatId" }
            val preparedMessage = preserveInvisiblePadding(msg)

            val intent =
                Intent().apply {
                    component =
                        ComponentName(
                            "com.kakao.talk", "com.kakao.talk.notification.NotificationActionService",
                        )
                    putExtra("noti_referer", referer)
                    putExtra("chat_id", chatId)

                    putExtra("is_chat_thread_notification", threadId != null)
                    if (threadId != null) {
                        putExtra("thread_id", threadId)
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
                IrisLogger.error("[Replier] AndroidHiddenApi.startService failed: ${e.message}")
                e.printStackTrace()
                throw e
            }
        }

        fun sendMessage(
            referer: String,
            chatId: Long,
            msg: String,
            threadId: Long?,
        ) {
            coroutineScope.launch {
                IrisLogger.debugLazy { "[Replier] sendMessage called: chatId=$chatId, msg='${msg.take(LOG_MESSAGE_PREVIEW_LENGTH)}...'" }
                messageChannel.send(
                    SendMessageRequest {
                        IrisLogger.debugLazy { "[Replier] sendMessageInternal executing for chatId=$chatId" }
                        sendMessageInternal(
                            referer,
                            chatId,
                            msg,
                            threadId,
                        )
                        IrisLogger.debugLazy { "[Replier] sendMessageInternal completed for chatId=$chatId" }
                    },
                )
                IrisLogger.debug("[Replier] Message queued to channel successfully")
            }
        }

        fun sendPhoto(
            room: Long,
            base64ImageDataString: String,
        ) {
            coroutineScope.launch {
                messageChannel.send(
                    SendMessageRequest {
                        sendPhotoInternal(
                            room,
                            base64ImageDataString,
                        )
                    },
                )
            }
        }

        fun sendMultiplePhotos(
            room: Long,
            base64ImageDataStrings: List<String>,
        ) {
            coroutineScope.launch {
                messageChannel.send(
                    SendMessageRequest {
                        sendMultiplePhotosInternal(
                            room,
                            base64ImageDataStrings,
                        )
                    },
                )
            }
        }

        private fun sendPhotoInternal(
            room: Long,
            base64ImageDataString: String,
        ) {
            sendMultiplePhotosInternal(room, listOf(base64ImageDataString))
        }

        private fun sendMultiplePhotosInternal(
            room: Long,
            base64ImageDataStrings: List<String>,
        ) {
            val picDir =
                File(IMAGE_DIR_PATH).apply {
                    if (!exists()) {
                        mkdirs()
                    }
                }

            val uris =
                base64ImageDataStrings.mapIndexed { idx, base64ImageDataString ->
                    val decodedImage = Base64.decode(base64ImageDataString, Base64.DEFAULT)
                    val timestamp = System.currentTimeMillis().toString()

                    val imageFile =
                        File(picDir, "${timestamp}_$idx.png").apply {
                            writeBytes(decodedImage)
                        }

                    val imageUri = Uri.fromFile(imageFile)
                    mediaScan(imageUri)
                    imageUri
                }

            if (uris.isEmpty()) {
                IrisLogger.error("No image URIs created, cannot send multiple photos.")
                return
            }

            val intent =
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    setPackage("com.kakao.talk")
                    type = "image/*"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                    putExtra("key_id", room)
                    putExtra("key_type", 1)
                    putExtra("key_from_direct_share", true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }

            try {
                AndroidHiddenApi.startActivity(intent)
            } catch (e: Exception) {
                IrisLogger.error("Error starting activity for sending multiple photos: $e")
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
