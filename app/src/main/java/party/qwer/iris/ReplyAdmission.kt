package party.qwer.iris

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.jsonPrimitive
import party.qwer.iris.model.ReplyRequest
import party.qwer.iris.model.ReplyType

enum class ReplyAdmissionStatus {
    ACCEPTED,
    QUEUE_FULL,
    SHUTDOWN,
    INVALID_PAYLOAD,
}

data class ReplyAdmissionResult(
    val status: ReplyAdmissionStatus,
    val message: String? = null,
)

internal fun replyAdmissionHttpStatus(status: ReplyAdmissionStatus): HttpStatusCode =
    when (status) {
        ReplyAdmissionStatus.ACCEPTED -> HttpStatusCode.Accepted
        ReplyAdmissionStatus.QUEUE_FULL -> HttpStatusCode.TooManyRequests
        ReplyAdmissionStatus.SHUTDOWN -> HttpStatusCode.ServiceUnavailable
        ReplyAdmissionStatus.INVALID_PAYLOAD -> HttpStatusCode.BadRequest
    }

internal fun supportsThreadReply(replyType: ReplyType): Boolean = replyType == ReplyType.TEXT || replyType == ReplyType.IMAGE || replyType == ReplyType.IMAGE_MULTIPLE || replyType == ReplyType.MARKDOWN

internal suspend fun admitReply(
    replyRequest: ReplyRequest,
    roomId: Long,
    notificationReferer: String,
    threadId: Long?,
    threadScope: Int?,
    messageSender: MessageSender,
    requestId: String? = null,
): ReplyAdmissionResult = dispatchReply(replyRequest, roomId, notificationReferer, threadId, threadScope, messageSender, requestId)

private suspend fun dispatchReply(
    replyRequest: ReplyRequest,
    roomId: Long,
    notificationReferer: String,
    threadId: Long?,
    threadScope: Int?,
    messageSender: MessageSender,
    requestId: String? = null,
): ReplyAdmissionResult =
    when (replyRequest.type) {
        ReplyType.TEXT -> messageSender.sendMessageSuspend(notificationReferer, roomId, extractTextPayload(replyRequest), threadId, threadScope, requestId)
        ReplyType.IMAGE, ReplyType.IMAGE_MULTIPLE -> throw ApiRequestException("image types require multipart/form-data")
        ReplyType.MARKDOWN -> messageSender.sendReplyMarkdownSuspend(roomId, extractTextPayload(replyRequest), threadId, threadScope, requestId)
    }

internal fun extractTextPayload(replyRequest: ReplyRequest): String =
    runCatching { replyRequest.data?.jsonPrimitive?.content }
        .getOrElse { throw ApiRequestException("text replies require string data") }
        ?: throw ApiRequestException("text replies require string data")
