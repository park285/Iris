package party.qwer.iris.reply

import party.qwer.iris.storage.ChatId

@JvmInline
internal value class ReplyThreadId(
    val value: Long,
)

internal data class ReplyTarget(
    val chatId: ChatId,
    val threadId: ReplyThreadId?,
)

internal sealed interface ReplyCommand {
    val requestId: String?
    val chatId: Long
        get() = target.chatId.value
    val threadId: Long?
        get() = target.threadId?.value
    val target: ReplyTarget
}

internal data class TextReplyCommand(
    override val target: ReplyTarget,
    val referer: String,
    val message: String,
    val threadScope: Int?,
    override val requestId: String?,
) : ReplyCommand

internal data class NativeImageReplyCommand(
    override val target: ReplyTarget,
    val imageCount: Int,
    val threadScope: Int?,
    override val requestId: String?,
) : ReplyCommand

internal data class ShareReplyCommand(
    override val target: ReplyTarget,
    val message: String,
    val threadScope: Int?,
    override val requestId: String?,
) : ReplyCommand
