package party.qwer.iris.reply

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
            chatId = chatId,
            referer = referer,
            message = message,
            threadId = threadId,
            threadScope = threadScope,
            requestId = requestId,
        )

    fun nativeImageReply(
        chatId: Long,
        base64Images: List<String>,
        threadId: Long?,
        threadScope: Int?,
        requestId: String?,
    ): NativeImageReplyCommand {
        require(base64Images.isNotEmpty()) { "image list must not be empty" }
        return NativeImageReplyCommand(
            chatId = chatId,
            base64Images = base64Images,
            threadId = threadId,
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
            chatId = chatId,
            message = message,
            threadId = threadId,
            threadScope = threadScope,
            requestId = requestId,
        )
}
