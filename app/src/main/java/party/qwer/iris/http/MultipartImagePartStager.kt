package party.qwer.iris.http

import io.ktor.http.content.PartData
import io.ktor.utils.io.jvm.javaio.toInputStream
import party.qwer.iris.VerifiedImagePayloadHandle
import party.qwer.iris.detectImageFormat
import party.qwer.iris.imageFormatFromContentType
import party.qwer.iris.invalidRequest
import party.qwer.iris.model.ReplyImagePartSpec

internal fun interface MultipartImagePartStager {
    fun stage(
        part: PartData.FileItem,
        expectedPart: ReplyImagePartSpec,
        replyImageIngressPolicy: ReplyImageIngressPolicy,
    ): VerifiedImagePayloadHandle
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
