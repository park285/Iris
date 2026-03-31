package party.qwer.iris.http

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.MultiPartData
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
import party.qwer.iris.VerifiedImagePayloadHandle
import party.qwer.iris.admitReply
import party.qwer.iris.detectImageFormat
import party.qwer.iris.imageFormatFromContentType
import party.qwer.iris.invalidRequest
import party.qwer.iris.model.ReplyAcceptedResponse
import party.qwer.iris.model.ReplyImageMetadata
import party.qwer.iris.model.ReplyImagePartSpec
import party.qwer.iris.model.ReplyRequest
import party.qwer.iris.model.ReplyStatusSnapshot
import party.qwer.iris.model.ReplyType
import party.qwer.iris.replyAdmissionHttpStatus
import party.qwer.iris.requestRejected
import party.qwer.iris.sha256Hex
import party.qwer.iris.supportsThreadReply
import party.qwer.iris.validateReplyImageManifest
import party.qwer.iris.validateReplyMarkdownThreadMetadata
import party.qwer.iris.validateReplyThreadScope
import java.util.UUID

private typealias MultipartImageStager = (PartData.FileItem, ReplyImagePartSpec, ReplyImageIngressPolicy) -> VerifiedImagePayloadHandle

internal fun Route.installReplyRoutes(
    authSupport: AuthSupport,
    serverJson: Json,
    notificationReferer: String,
    messageSender: MessageSender,
    replyStatusProvider: ((String) -> ReplyStatusSnapshot?)?,
    replyImageIngressPolicy: ReplyImageIngressPolicy = ReplyImageIngressPolicy.fromEnv(),
    multipartImageStager: MultipartImageStager = ::stageMultipartImagePart,
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

            call.request.headers[HttpHeaders.ContentType]?.startsWith(ContentType.Application.Json.toString(), ignoreCase = true) == true -> {
                if (
                    !withVerifiedProtectedBody(
                        call = call,
                        maxBodyBytes = replyImageIngressPolicy.imagePolicy.jsonReplyBodyMaxBytes,
                        bodyReader = protectedBodyReader,
                        precheck = { authSupport.precheckBotControlSignature(call, method = "POST") },
                        finalize = { precheck, actualBodySha256Hex -> authSupport.finalizeSignature(call, precheck, actualBodySha256Hex) },
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
    multipartImageStager: MultipartImageStager,
): ReplyAcceptedResponse? {
    val imagePolicy = replyImageIngressPolicy.imagePolicy
    val multipart = call.receiveMultipart(formFieldLimit = imagePolicy.maxMetadataBytes.toLong())
    val imageHandles = mutableListOf<VerifiedImagePayloadHandle>()
    try {
        var metadata: ReplyImageMetadata? = null
        var nextExpectedImageIndex = 0
        var totalImageBytes = 0L

        while (true) {
            val part = multipart.readPart() ?: break
            try {
                when (part) {
                    is PartData.FormItem -> {
                        if (part.name != "metadata") continue
                        if (metadata != null) {
                            invalidRequest("duplicate metadata part")
                        }
                        val rawMetadata = part.value.toByteArray(Charsets.UTF_8)
                        if (rawMetadata.size > imagePolicy.maxMetadataBytes) {
                            requestRejected("metadata part too large", HttpStatusCode.PayloadTooLarge)
                        }
                        if (!authSupport.requireBotControlSignature(call, method = "POST", bodySha256Hex = sha256Hex(rawMetadata))) {
                            discardRemainingMultipartParts(multipart)
                            return null
                        }
                        metadata = decodeReplyImageMetadata(serverJson, rawMetadata)
                        validateMultipartImageManifest(metadata, replyImageIngressPolicy)
                    }

                    is PartData.FileItem -> {
                        if (part.name != "image") continue
                        val currentMetadata = metadata ?: invalidRequest("metadata part must precede image parts")
                        val expectedPart =
                            currentMetadata.images.getOrNull(nextExpectedImageIndex)
                                ?: invalidRequest("unexpected extra image part")
                        validateExpectedPartIndex(expectedPart, nextExpectedImageIndex)
                        val handle = multipartImageStager(part, expectedPart, replyImageIngressPolicy)
                        totalImageBytes =
                            accumulateReplyBodyBytes(
                                current = totalImageBytes,
                                partBytes = handle.sizeBytes,
                                maxBytes = imagePolicy.maxTotalBytes.toLong(),
                            )
                        imageHandles += handle
                        nextExpectedImageIndex += 1
                    }

                    else -> Unit
                }
            } finally {
                part.dispose()
            }
        }

        val currentMetadata = metadata ?: invalidRequest("missing metadata part")
        if (imageHandles.size != currentMetadata.images.size) {
            invalidRequest("missing image part")
        }

        val requestId = "reply-${UUID.randomUUID()}"
        val roomId = currentMetadata.room.toLongOrNull() ?: invalidRequest("room must be a numeric string")
        val threadId =
            currentMetadata.threadId?.let {
                it.toLongOrNull() ?: invalidRequest("threadId must be a numeric string")
            }
        val threadScope =
            try {
                validateReplyThreadScope(currentMetadata.type, threadId, currentMetadata.threadScope)
            } catch (e: IllegalArgumentException) {
                invalidRequest(e.message ?: "invalid threadScope")
            }
        if (threadId != null && !supportsThreadReply(currentMetadata.type)) {
            invalidRequest("threadId is not supported for this reply type")
        }

        val admission =
            messageSender.sendNativeMultiplePhotosHandlesSuspend(
                room = roomId,
                imageHandles = imageHandles.toList(),
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
        imageHandles.clear()

        return ReplyAcceptedResponse(
            requestId = requestId,
            room = currentMetadata.room,
            type = currentMetadata.type,
        )
    } finally {
        imageHandles.forEach { handle ->
            runCatching { handle.close() }
        }
    }
}

private fun isMultipartFormData(call: ApplicationCall): Boolean = call.request.headers[HttpHeaders.ContentType]?.startsWith("multipart/form-data", ignoreCase = true) == true

private fun requireMultipartImageType(type: ReplyType) {
    if (type != ReplyType.IMAGE && type != ReplyType.IMAGE_MULTIPLE) {
        invalidRequest("multipart/form-data is only supported for image replies")
    }
}

private fun accumulateReplyBodyBytes(
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

private fun decodeReplyImageMetadata(
    serverJson: Json,
    rawMetadata: ByteArray,
): ReplyImageMetadata =
    try {
        serverJson.decodeFromString(ReplyImageMetadata.serializer(), rawMetadata.decodeToString())
    } catch (_: SerializationException) {
        invalidRequest("invalid metadata part")
    }

private fun validateMultipartImageManifest(
    metadata: ReplyImageMetadata,
    replyImageIngressPolicy: ReplyImageIngressPolicy,
) {
    requireMultipartImageType(metadata.type)
    try {
        validateReplyImageManifest(metadata.type, metadata.images, replyImageIngressPolicy.imagePolicy)
    } catch (error: IllegalArgumentException) {
        invalidRequest(error.message ?: "invalid image metadata")
    }
}

private fun validateExpectedPartIndex(
    expectedPart: ReplyImagePartSpec,
    expectedIndex: Int,
) {
    if (expectedPart.index != expectedIndex) {
        invalidRequest("metadata images must be ordered by index")
    }
}

internal fun stageMultipartImagePart(
    part: PartData.FileItem,
    expectedPart: ReplyImagePartSpec,
    replyImageIngressPolicy: ReplyImageIngressPolicy,
): VerifiedImagePayloadHandle {
    val imagePolicy = replyImageIngressPolicy.imagePolicy
    val actualContentType =
        part.contentType
            ?.toString()
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase()
    val expectedContentType =
        expectedPart.contentType
            .substringBefore(';')
            .trim()
            .lowercase()
    if (expectedContentType !in imagePolicy.allowedContentTypes) {
        invalidRequest("unexpected image content type")
    }
    if (actualContentType == null || !expectedContentType.equals(actualContentType, ignoreCase = true)) {
        invalidRequest("unexpected image content type")
    }
    val handle =
        part.provider().toInputStream().use { input ->
            readInputStreamWithStreamingDigest(
                input = input,
                declaredContentLength = expectedPart.byteLength,
                maxBodyBytes = expectedPart.byteLength.toInt(),
                bufferingPolicy = replyImageIngressPolicy.bufferingPolicy,
            )
        }
    if (!handle.sha256Hex.equals(expectedPart.sha256Hex, ignoreCase = true)) {
        runCatching { handle.close() }
        invalidRequest("image digest mismatch")
    }
    if (handle.sizeBytes != expectedPart.byteLength) {
        runCatching { handle.close() }
        invalidRequest("image length mismatch")
    }
    val detectedFormat =
        handle.openInputStream().use { input ->
            detectImageFormat(input.readNBytes(32))
        }
    if (detectedFormat == null || detectedFormat != imageFormatFromContentType(expectedContentType)) {
        runCatching { handle.close() }
        invalidRequest("unknown or mismatched image format")
    }
    return VerifiedRequestBodyImageHandle(
        delegate = handle,
        format = detectedFormat,
        contentType = expectedContentType,
    )
}

private data class VerifiedRequestBodyImageHandle(
    private val delegate: RequestBodyHandle,
    override val format: party.qwer.iris.ImageFormat,
    override val contentType: String,
) : VerifiedImagePayloadHandle {
    override val sha256Hex: String
        get() = delegate.sha256Hex

    override val sizeBytes: Long
        get() = delegate.sizeBytes

    override fun openInputStream() = delegate.openInputStream()

    override fun close() = delegate.close()
}

private suspend fun discardRemainingMultipartParts(multipart: MultiPartData) {
    while (true) {
        val remainingPart = multipart.readPart() ?: break
        remainingPart.dispose()
    }
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
