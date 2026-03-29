package party.qwer.iris.reply

import party.qwer.iris.IrisLogger
import party.qwer.iris.NativeImageReplySender
import party.qwer.iris.PreparedImages

internal interface MediaPreparationCleanup {
    fun cleanup(preparedImages: PreparedImages)
}

internal class ReplyTransport(
    private val notificationReplySender: (String, Long, CharSequence, Long?, Int?) -> Unit,
    private val sharedTextReplySender: (Long, CharSequence, Long?, Int?) -> Unit,
    private val nativeImageReplySender: NativeImageReplySender,
    private val mediaPreparationService: MediaPreparationCleanup?,
) {
    private companion object {
        private val zeroWidthCharacters = setOf('\u200B', '\u200C', '\u200D', '\u2060', '\uFEFF')
        private const val ZERO_WIDTH_NO_BREAK_SPACE = "\uFEFF"
    }

    fun sendText(command: TextReplyCommand) {
        if (command.threadId != null) {
            val preparedMessage = preserveInvisiblePadding(command.message)
            sharedTextReplySender(
                command.chatId,
                preparedMessage,
                command.threadId,
                command.threadScope ?: 2,
            )
        } else {
            val preparedMessage = preserveInvisiblePadding(command.message)
            notificationReplySender(
                command.referer,
                command.chatId,
                preparedMessage,
                command.threadId,
                command.threadScope,
            )
        }
    }

    fun sendShare(command: ShareReplyCommand) {
        val preparedMessage = preserveInvisiblePadding(command.message)
        sharedTextReplySender(
            command.chatId,
            preparedMessage,
            command.threadId,
            command.threadScope,
        )
    }

    fun sendNativeImages(
        command: NativeImageReplyCommand,
        preparedImages: PreparedImages,
    ) {
        try {
            IrisLogger.info(
                "[ReplyTransport] sendNativeImages room=${preparedImages.room} " +
                    "threadId=${command.threadId} scope=${command.threadScope} " +
                    "imageCount=${preparedImages.imagePaths.size} requestId=${command.requestId}",
            )
            nativeImageReplySender.send(
                roomId = preparedImages.room,
                imagePaths = preparedImages.imagePaths,
                threadId = command.threadId,
                threadScope = command.threadScope,
                requestId = command.requestId,
            )
        } catch (e: Exception) {
            IrisLogger.error("Error sending native reply-image: $e")
            mediaPreparationService?.cleanup(preparedImages)
            throw e
        }
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

        val builder = StringBuilder(message.length + 4)
        message.forEach { ch ->
            builder.append(ch)
            if (ch == '\u200B') {
                builder.append(ZERO_WIDTH_NO_BREAK_SPACE)
            }
        }
        return builder.toString()
    }
}
