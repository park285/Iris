package party.qwer.iris.http

import party.qwer.iris.invalidRequest
import party.qwer.iris.model.ReplyImageMetadata
import party.qwer.iris.model.ReplyRequest
import party.qwer.iris.model.ReplyType
import party.qwer.iris.supportsThreadReply
import party.qwer.iris.validateReplyMarkdownThreadMetadata
import party.qwer.iris.validateReplyThreadScope

internal data class ValidatedReplyTarget(
    val roomId: Long,
    val threadId: Long?,
    val threadScope: Int?,
)

internal fun validateReplyTarget(replyRequest: ReplyRequest): ValidatedReplyTarget =
    validateReplyTarget(
        replyType = replyRequest.type,
        room = replyRequest.room,
        threadIdValue = replyRequest.threadId,
        threadScopeValue = replyRequest.threadScope,
    )

internal fun validateReplyTarget(metadata: ReplyImageMetadata): ValidatedReplyTarget =
    validateReplyTarget(
        replyType = metadata.type,
        room = metadata.room,
        threadIdValue = metadata.threadId,
        threadScopeValue = metadata.threadScope,
    )

private fun validateReplyTarget(
    replyType: ReplyType,
    room: String,
    threadIdValue: String?,
    threadScopeValue: Int?,
): ValidatedReplyTarget {
    val roomId = room.toLongOrNull() ?: invalidRequest("room must be a numeric string")
    val threadId =
        threadIdValue?.let {
            it.toLongOrNull() ?: invalidRequest("threadId must be a numeric string")
        }
    val threadScope =
        if (replyType == ReplyType.MARKDOWN) {
            try {
                validateReplyMarkdownThreadMetadata(threadId, threadScopeValue)
            } catch (e: IllegalArgumentException) {
                invalidRequest(e.message ?: "invalid thread metadata")
            }
        } else {
            try {
                validateReplyThreadScope(replyType, threadId, threadScopeValue)
            } catch (e: IllegalArgumentException) {
                invalidRequest(e.message ?: "invalid threadScope")
            }
        }
    if (threadId != null && !supportsThreadReply(replyType)) {
        invalidRequest("threadId is not supported for this reply type")
    }
    return ValidatedReplyTarget(
        roomId = roomId,
        threadId = threadId,
        threadScope = threadScope,
    )
}
