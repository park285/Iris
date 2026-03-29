package party.qwer.iris.http

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.json.Json
import party.qwer.iris.ApiRequestException
import party.qwer.iris.MessageSender
import party.qwer.iris.ReplyAdmissionStatus
import party.qwer.iris.admitReply
import party.qwer.iris.invalidRequest
import party.qwer.iris.model.ReplyAcceptedResponse
import party.qwer.iris.model.ReplyRequest
import party.qwer.iris.model.ReplyStatusSnapshot
import party.qwer.iris.model.ReplyType
import party.qwer.iris.replyAdmissionHttpStatus
import party.qwer.iris.requestRejected
import party.qwer.iris.supportsThreadReply
import party.qwer.iris.validateReplyMarkdownThreadMetadata
import party.qwer.iris.validateReplyThreadScope
import java.util.UUID

private const val MAX_REPLY_REQUEST_BODY_BYTES = 48 * 1024 * 1024

internal fun Route.installReplyRoutes(
    authSupport: AuthSupport,
    serverJson: Json,
    notificationReferer: String,
    messageSender: MessageSender,
    replyStatusProvider: ((String) -> ReplyStatusSnapshot?)?,
) {
    post("/reply") {
        val rawBody = readProtectedBody(call, MAX_REPLY_REQUEST_BODY_BYTES)
        if (!authSupport.requireBotToken(call, method = "POST", body = rawBody.body, bodySha256Hex = rawBody.sha256Hex)) {
            return@post
        }

        val replyRequest = serverJson.decodeFromString<ReplyRequest>(rawBody.body)
        val response = enqueueReply(replyRequest, notificationReferer, messageSender)
        call.respond(HttpStatusCode.Accepted, response)
    }

    get("/reply-status/{requestId}") {
        if (!authSupport.requireBotToken(call, method = "GET")) {
            return@get
        }
        val requestId = call.parameters["requestId"] ?: invalidRequest("missing requestId")
        val snapshot =
            replyStatusProvider?.invoke(requestId)
                ?: throw ApiRequestException("reply status not found", HttpStatusCode.NotFound)
        call.respond(snapshot)
    }
}

private fun enqueueReply(
    replyRequest: ReplyRequest,
    notificationReferer: String,
    messageSender: MessageSender,
): ReplyAcceptedResponse {
    val requestId = "reply-${UUID.randomUUID()}"
    val roomId = replyRequest.room.toLongOrNull() ?: invalidRequest("room must be a numeric string")
    val threadId =
        replyRequest.threadId?.let {
            it.toLongOrNull() ?: invalidRequest("threadId must be a numeric string")
        }
    val threadScope =
        if (replyRequest.type == ReplyType.MARKDOWN) {
            try {
                validateReplyMarkdownThreadMetadata(threadId, replyRequest.threadScope)
            } catch (e: IllegalArgumentException) {
                invalidRequest(e.message ?: "invalid thread metadata")
            }
        } else {
            try {
                validateReplyThreadScope(replyRequest.type, threadId, replyRequest.threadScope)
            } catch (e: IllegalArgumentException) {
                invalidRequest(e.message ?: "invalid threadScope")
            }
        }
    if (threadId != null && !supportsThreadReply(replyRequest.type)) {
        invalidRequest("threadId is not supported for this reply type")
    }

    val admission =
        admitReply(
            replyRequest,
            roomId,
            notificationReferer,
            threadId,
            threadScope,
            messageSender,
            requestId,
        )
    if (admission.status != ReplyAdmissionStatus.ACCEPTED) {
        requestRejected(
            admission.message ?: "reply request rejected",
            replyAdmissionHttpStatus(admission.status),
        )
    }

    return ReplyAcceptedResponse(
        requestId = requestId,
        room = replyRequest.room,
        type = replyRequest.type,
    )
}
