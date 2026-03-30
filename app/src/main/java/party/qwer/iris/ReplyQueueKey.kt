package party.qwer.iris

import party.qwer.iris.reply.ReplyThreadId
import party.qwer.iris.storage.ChatId

internal data class ReplyQueueKey(
    val chatId: ChatId,
    val threadId: ReplyThreadId?,
) {
    constructor(
        chatId: Long,
        threadId: Long?,
    ) : this(
        chatId = ChatId(chatId),
        threadId = threadId?.let(::ReplyThreadId),
    )
}
