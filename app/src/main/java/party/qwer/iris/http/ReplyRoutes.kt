package party.qwer.iris.http

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import party.qwer.iris.ApiRequestException
import party.qwer.iris.MessageSender
import party.qwer.iris.ReplyAdmissionStatus
import party.qwer.iris.admitReply
import party.qwer.iris.invalidRequest
import party.qwer.iris.model.ReplyAcceptedResponse
import party.qwer.iris.model.ReplyImageMetadata
import party.qwer.iris.model.ReplyRequest
import party.qwer.iris.model.ReplyStatusSnapshot
import party.qwer.iris.model.ReplyType
import party.qwer.iris.replyAdmissionHttpStatus
import party.qwer.iris.requestRejected
import party.qwer.iris.sha256Hex
import party.qwer.iris.supportsThreadReply
import party.qwer.iris.validateReplyMarkdownThreadMetadata
import party.qwer.iris.validateReplyThreadScope
import java.io.ByteArrayOutputStream
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
        when {
            isMultipartFormData(call) -> {
                val response =
                    enqueueMultipartReply(
                        call = call,
                        authSupport = authSupport,
                        serverJson = serverJson,
                        messageSender = messageSender,
                    ) ?: return@post
                call.respond(HttpStatusCode.Accepted, response)
            }

            call.request.headers[HttpHeaders.ContentType]?.startsWith(ContentType.Application.Json.toString(), ignoreCase = true) == true -> {
                readProtectedBody(call, MAX_REPLY_REQUEST_BODY_BYTES).use { rawBody ->
                    if (!authSupport.requireBotControlSignature(call, method = "POST", bodySha256Hex = rawBody.sha256Hex)) {
                        return@post
                    }

                    val replyRequest = rawBody.decodeJson(serverJson, ReplyRequest.serializer())
                    val response = enqueueReply(replyRequest, notificationReferer, messageSender)
                    call.respond(HttpStatusCode.Accepted, response)
                }
            }

            else -> invalidRequest("unsupported content type")
        }
    }

    get("/reply-status/{requestId}") {
        if (!authSupport.requireBotControlSignature(call, method = "GET")) {
            return@get
        }
        val requestId = call.parameters["requestId"] ?: invalidRequest("missing requestId")
        val snapshot =
            replyStatusProvider?.invoke(requestId)
                ?: throw ApiRequestException("reply status not found", HttpStatusCode.NotFound)
        call.respond(snapshot)
    }
}

private suspend fun enqueueMultipartReply(
    call: ApplicationCall,
    authSupport: AuthSupport,
    serverJson: Json,
    messageSender: MessageSender,
): ReplyAcceptedResponse? {
    val multipart = call.receiveMultipart(formFieldLimit = MAX_REPLY_REQUEST_BODY_BYTES.toLong())
    var totalBytes = 0L
    var metadataBytes: ByteArray? = null
    val imageBytesList = mutableListOf<ByteArray>()

    while (true) {
        val part = multipart.readPart() ?: break
        try {
            when (part) {
                is PartData.FormItem -> {
                    if (part.name == "metadata") {
                        val bytes = part.value.toByteArray(Charsets.UTF_8)
                        totalBytes = accumulateReplyBodyBytes(totalBytes, bytes.size.toLong())
                        metadataBytes = bytes
                    }
                }

                is PartData.FileItem -> {
                    if (part.name == "image") {
                        val bytes = readImagePartBytes(part)
                        totalBytes = accumulateReplyBodyBytes(totalBytes, bytes.size.toLong())
                        imageBytesList += bytes
                    }
                }

                else -> Unit
            }
        } finally {
            part.dispose()
        }
    }

    val rawMetadata = metadataBytes ?: invalidRequest("missing metadata part")
    if (!authSupport.requireBotControlSignature(call, method = "POST", bodySha256Hex = sha256Hex(rawMetadata))) {
        return null
    }

    val metadata =
        try {
            serverJson.decodeFromString(ReplyImageMetadata.serializer(), rawMetadata.decodeToString())
        } catch (_: SerializationException) {
            invalidRequest("invalid metadata part")
        }
    requireMultipartImageType(metadata.type)
    if (imageBytesList.isEmpty()) {
        invalidRequest("missing image part")
    }

    val requestId = "reply-${UUID.randomUUID()}"
    val roomId = metadata.room.toLongOrNull() ?: invalidRequest("room must be a numeric string")
    val threadId =
        metadata.threadId?.let {
            it.toLongOrNull() ?: invalidRequest("threadId must be a numeric string")
        }
    val threadScope =
        try {
            validateReplyThreadScope(metadata.type, threadId, metadata.threadScope)
        } catch (e: IllegalArgumentException) {
            invalidRequest(e.message ?: "invalid threadScope")
        }
    if (threadId != null && !supportsThreadReply(metadata.type)) {
        invalidRequest("threadId is not supported for this reply type")
    }

    val admission =
        messageSender.sendNativeMultiplePhotosBytesSuspend(
            room = roomId,
            imageBytesList = imageBytesList,
            threadId = threadId,
            threadScope = threadScope,
            requestId = requestId,
        )
    if (admission.status != ReplyAdmissionStatus.ACCEPTED) {
        requestRejected(
            admission.message ?: "reply request rejected",
            replyAdmissionHttpStatus(admission.status),
        )
    }

    return ReplyAcceptedResponse(
        requestId = requestId,
        room = metadata.room,
        type = metadata.type,
    )
}

private fun isMultipartFormData(call: ApplicationCall): Boolean =
    call.request.headers[HttpHeaders.ContentType]?.startsWith("multipart/form-data", ignoreCase = true) == true

private fun requireMultipartImageType(type: ReplyType) {
    if (type != ReplyType.IMAGE && type != ReplyType.IMAGE_MULTIPLE) {
        invalidRequest("multipart/form-data is only supported for image replies")
    }
}

private fun readImagePartBytes(part: PartData.FileItem): ByteArray {
    val output = ByteArrayOutputStream()
    part.provider().toInputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) {
                break
            }
            if (read == 0) {
                continue
            }
            output.write(buffer, 0, read)
            if (output.size().toLong() > MAX_REPLY_REQUEST_BODY_BYTES) {
                requestRejected("request body too large", HttpStatusCode.PayloadTooLarge)
            }
        }
    }
    return output.toByteArray()
}

private fun accumulateReplyBodyBytes(
    current: Long,
    partBytes: Long,
): Long {
    val next = current + partBytes
    if (next > MAX_REPLY_REQUEST_BODY_BYTES) {
        requestRejected("request body too large", HttpStatusCode.PayloadTooLarge)
    }
    return next
}

private suspend fun enqueueReply(
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
