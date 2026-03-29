package party.qwer.iris.reply

sealed interface ReplyCommand {
    val requestId: String?
    val chatId: Long
    val threadId: Long?
}

data class TextReplyCommand(
    override val chatId: Long,
    val referer: String,
    val message: String,
    override val threadId: Long?,
    val threadScope: Int?,
    override val requestId: String?,
) : ReplyCommand

data class NativeImageReplyCommand(
    override val chatId: Long,
    val base64Images: List<String>,
    override val threadId: Long?,
    val threadScope: Int?,
    override val requestId: String?,
) : ReplyCommand

data class ShareReplyCommand(
    override val chatId: Long,
    val message: String,
    override val threadId: Long?,
    val threadScope: Int?,
    override val requestId: String?,
) : ReplyCommand
