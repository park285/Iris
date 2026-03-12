package party.qwer.iris

import io.ktor.http.HttpStatusCode
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

internal fun supportsThreadReply(replyType: ReplyType): Boolean = replyType == ReplyType.TEXT
