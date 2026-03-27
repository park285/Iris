package party.qwer.iris

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.jsonArray
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

internal fun supportsThreadReply(replyType: ReplyType): Boolean = replyType == ReplyType.TEXT || replyType == ReplyType.IMAGE || replyType == ReplyType.IMAGE_MULTIPLE

internal fun admitReply(
    replyRequest: ReplyRequest,
    roomId: Long,
    notificationReferer: String,
    threadId: Long?,
    threadScope: Int?,
    messageSender: MessageSender,
): ReplyAdmissionResult = dispatchReply(replyRequest, roomId, notificationReferer, threadId, threadScope, messageSender)

private fun dispatchReply(
    replyRequest: ReplyRequest,
    roomId: Long,
    notificationReferer: String,
    threadId: Long?,
    threadScope: Int?,
    messageSender: MessageSender,
): ReplyAdmissionResult =
    when (replyRequest.type) {
        ReplyType.TEXT -> messageSender.sendMessage(notificationReferer, roomId, extractTextPayload(replyRequest), threadId, threadScope)
        ReplyType.IMAGE -> messageSender.sendPhoto(roomId, extractSingleImagePayload(replyRequest), threadId, threadScope)
        ReplyType.IMAGE_MULTIPLE -> messageSender.sendMultiplePhotos(roomId, extractImagePayloads(replyRequest), threadId, threadScope)
    }

internal fun extractTextPayload(replyRequest: ReplyRequest): String =
    runCatching { replyRequest.data.jsonPrimitive.content }
        .getOrElse { throw ApiRequestException("text replies require string data") }

private fun extractSingleImagePayload(replyRequest: ReplyRequest): String = extractTextPayload(replyRequest)

internal fun extractImagePayloads(replyRequest: ReplyRequest): List<String> =
    runCatching {
        replyRequest.data.jsonArray.map { element -> element.jsonPrimitive.content }
    }.getOrElse {
        throw ApiRequestException("image_multiple replies require a JSON array of base64 strings")
    }
