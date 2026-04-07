package party.qwer.iris.http

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveMultipart
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
import party.qwer.iris.model.ReplyImagePartSpec
import party.qwer.iris.model.ReplyRequest
import party.qwer.iris.model.ReplyStatusSnapshot
import party.qwer.iris.model.ReplyType
import party.qwer.iris.replyAdmissionHttpStatus
import party.qwer.iris.requestRejected
import party.qwer.iris.validateReplyImageManifest
import java.util.UUID

internal fun Route.installReplyRoutes(
    authSupport: AuthSupport,
    serverJson: Json,
    notificationReferer: String,
    messageSender: MessageSender,
    replyStatusProvider: ((String) -> ReplyStatusSnapshot?)?,
    replyImageIngressPolicy: ReplyImageIngressPolicy = ReplyImageIngressPolicy.fromEnv(),
    multipartImageStager: MultipartImagePartStager = MultipartImagePartStager(::stageMultipartImagePart),
    protectedBodyReader: ProtectedBodyReader = ::readProtectedBody,
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
                        replyImageIngressPolicy = replyImageIngressPolicy,
                        multipartImageStager = multipartImageStager,
                    ) ?: return@post
                call.respond(HttpStatusCode.Accepted, response)
            }

            call.request.headers[HttpHeaders.ContentType]?.startsWith(
                ContentType.Application.Json.toString(),
                ignoreCase = true,
            ) == true -> {
                if (
                    !withVerifiedProtectedBody(
                        call = call,
                        maxBodyBytes = replyImageIngressPolicy.imagePolicy.jsonReplyBodyMaxBytes,
                        bodyReader = protectedBodyReader,
                        precheck = { authSupport.precheckBotControlSignature(call, method = "POST") },
                        finalize = { precheck, actualBodySha256Hex ->
                            authSupport.finalizeSignature(
                                call,
                                precheck,
                                actualBodySha256Hex,
                            )
                        },
                    ) { rawBody ->
                        val replyRequest = rawBody.decodeJson(serverJson, ReplyRequest.serializer())
                        val response = enqueueReply(replyRequest, notificationReferer, messageSender)
                        call.respond(HttpStatusCode.Accepted, response)
                    }
                ) {
                    return@post
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
    replyImageIngressPolicy: ReplyImageIngressPolicy,
    multipartImageStager: MultipartImagePartStager,
): ReplyAcceptedResponse? {
    val precheck = authSupport.precheckBotControlSignature(call, method = "POST") ?: return null
    val imagePolicy = replyImageIngressPolicy.imagePolicy
    val multipart = call.receiveMultipart(formFieldLimit = imagePolicy.maxSingleImageBytes.toLong())
    val collector =
        MultipartReplyCollector(
            call = call,
            authSupport = authSupport,
            serverJson = serverJson,
            precheck = precheck,
            replyImageIngressPolicy = replyImageIngressPolicy,
            imagePartStager = multipartImageStager,
        )
    try {
        /*
         * Multipart 업로드 필수 제약
         * 1. 이미지 본문을 받기 전에 metadata 인증이 먼저 완료되어야 한다.
         * 2. 이미지 파트는 manifest 순서대로 도착해야 한다.
         * 3. digest·length·content-type·감지된 포맷이 모두 일치해야 한다.
         * 4. reply admission 진입 전에 thread metadata 검증을 마쳐야 한다.
         */
        val payload = collector.collect(multipart) ?: return null
        payload.use { collected ->
            val requestId = "reply-${UUID.randomUUID()}"
            val handles = collected.takeImageHandles()
            val admission =
                try {
                    messageSender.sendNativeMultiplePhotosHandlesSuspend(
                        room = collected.target.roomId,
                        imageHandles = handles,
                        threadId = collected.target.threadId,
                        threadScope = collected.target.threadScope,
                        requestId = requestId,
                    )
                } catch (error: Throwable) {
                    handles.forEach { handle ->
                        runCatching { handle.close() }
                    }
                    throw error
                }
            if (admission.status != ReplyAdmissionStatus.ACCEPTED) {
                requestRejected(
                    admission.message ?: "reply request rejected",
                    replyAdmissionHttpStatus(admission.status),
                )
            }

            return ReplyAcceptedResponse(
                requestId = requestId,
                room = collected.metadata.room,
                type = collected.metadata.type,
            )
        }
    } finally {
        collector.closeUntransferredHandles()
    }
}

private fun isMultipartFormData(call: ApplicationCall): Boolean = call.request.headers[HttpHeaders.ContentType]?.startsWith("multipart/form-data", ignoreCase = true) == true

internal fun requireMultipartImageType(type: ReplyType) {
    if (type != ReplyType.IMAGE && type != ReplyType.IMAGE_MULTIPLE) {
        invalidRequest("multipart/form-data is only supported for image replies")
    }
}

internal fun accumulateReplyBodyBytes(
    current: Long,
    partBytes: Long,
    maxBytes: Long,
): Long {
    val next = current + partBytes
    if (next > maxBytes) {
        requestRejected("request body too large", HttpStatusCode.PayloadTooLarge)
    }
    return next
}

internal fun validateMultipartImageManifest(
    metadata: party.qwer.iris.model.ReplyImageMetadata,
    replyImageIngressPolicy: ReplyImageIngressPolicy,
) {
    requireMultipartImageType(metadata.type)
    try {
        validateReplyImageManifest(metadata.type, metadata.images, replyImageIngressPolicy.imagePolicy)
    } catch (error: IllegalArgumentException) {
        invalidRequest(error.message ?: "invalid image metadata")
    }
}

internal fun validateExpectedPartIndex(
    expectedPart: ReplyImagePartSpec,
    expectedIndex: Int,
) {
    if (expectedPart.index != expectedIndex) {
        invalidRequest("metadata images must be ordered by index")
    }
}

private suspend fun enqueueReply(
    replyRequest: ReplyRequest,
    notificationReferer: String,
    messageSender: MessageSender,
): ReplyAcceptedResponse {
    val requestId = "reply-${UUID.randomUUID()}"
    val target = validateReplyTarget(replyRequest)

    val admission =
        admitReply(
            replyRequest,
            target.roomId,
            notificationReferer,
            target.threadId,
            target.threadScope,
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
