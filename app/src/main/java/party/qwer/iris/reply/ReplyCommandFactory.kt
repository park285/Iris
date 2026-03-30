package party.qwer.iris.reply

import party.qwer.iris.storage.ChatId

internal class ReplyCommandFactory {
    fun textReply(
        referer: String,
        chatId: Long,
        message: String,
        threadId: Long?,
        threadScope: Int?,
        requestId: String?,
    ): TextReplyCommand =
        TextReplyCommand(
            target = replyTarget(chatId, threadId),
            referer = referer,
            message = message,
            threadScope = threadScope,
            requestId = requestId,
        )

    fun nativeImageReply(
        chatId: Long,
        imageCount: Int,
        threadId: Long?,
        threadScope: Int?,
        requestId: String?,
    ): NativeImageReplyCommand {
        require(imageCount > 0) { "image list must not be empty" }
        return NativeImageReplyCommand(
            target = replyTarget(chatId, threadId),
            imageCount = imageCount,
            threadScope = threadScope,
            requestId = requestId,
        )
    }

    fun shareReply(
        chatId: Long,
        message: String,
        threadId: Long?,
        threadScope: Int?,
        requestId: String?,
    ): ShareReplyCommand =
        ShareReplyCommand(
            target = replyTarget(chatId, threadId),
            message = message,
            threadScope = threadScope,
            requestId = requestId,
        )

    private fun replyTarget(
        chatId: Long,
        threadId: Long?,
    ): ReplyTarget =
        ReplyTarget(
            chatId = ChatId(chatId),
            threadId = threadId?.let(::ReplyThreadId),
        )
}
