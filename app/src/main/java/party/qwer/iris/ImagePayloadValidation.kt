package party.qwer.iris

import party.qwer.iris.model.ReplyImagePartSpec
import party.qwer.iris.model.ReplyType

internal const val MAX_REPLY_IMAGE_COUNT = 8
internal const val MAX_REPLY_IMAGE_METADATA_BYTES = 64 * 1024
internal const val MAX_TOTAL_REPLY_IMAGE_BYTES = 30 * 1024 * 1024

private val SHA256_HEX_PATTERN = Regex("^[0-9a-f]{64}$")

enum class ImageFormat {
    PNG,
    JPEG,
    WEBP,
    GIF,
}

internal fun detectImageFormat(imageBytes: ByteArray): ImageFormat? =
    when {
        isPngSignature(imageBytes) -> ImageFormat.PNG
        isJpegSignature(imageBytes) -> ImageFormat.JPEG
        isWebpSignature(imageBytes) -> ImageFormat.WEBP
        isGifSignature(imageBytes) -> ImageFormat.GIF
        else -> null
    }

internal fun imageFormatFromContentType(contentType: String): ImageFormat? =
    when (contentType.substringBefore(';').trim().lowercase()) {
        "image/png" -> ImageFormat.PNG
        "image/jpeg" -> ImageFormat.JPEG
        "image/webp" -> ImageFormat.WEBP
        "image/gif" -> ImageFormat.GIF
        else -> null
    }

internal fun contentTypeForImageFormat(format: ImageFormat): String =
    when (format) {
        ImageFormat.PNG -> "image/png"
        ImageFormat.JPEG -> "image/jpeg"
        ImageFormat.WEBP -> "image/webp"
        ImageFormat.GIF -> "image/gif"
    }

internal fun requireKnownImageFormat(imageBytes: ByteArray): ImageFormat =
    requireNotNull(
        detectImageFormat(imageBytes),
    ) { "unknown image format" }

internal fun validateImageBytesPayload(
    imageBytesList: List<ByteArray>,
    policy: ReplyImagePolicy = ReplyImagePolicy(),
): List<ByteArray> {
    validateImagePayloadSizes(
        imageSizes = imageBytesList.map { it.size.toLong() },
        policy = policy,
    )
    imageBytesList.forEach { bytes ->
        require(bytes.isNotEmpty()) { "empty image data" }
        requireKnownImageFormat(bytes)
    }
    return imageBytesList
}

internal fun validateImagePayloadSizes(
    imageSizes: List<Long>,
    policy: ReplyImagePolicy = ReplyImagePolicy(),
) {
    require(imageSizes.isNotEmpty()) { "no image data provided" }
    require(imageSizes.size <= policy.maxImagesPerRequest) { "too many images" }

    var totalBytes = 0L
    imageSizes.forEach { sizeBytes ->
        require(sizeBytes > 0L) { "empty image data" }
        require(sizeBytes <= policy.maxSingleImageBytes.toLong()) { "payload exceeds size limit" }
        totalBytes += sizeBytes
        require(totalBytes <= policy.maxTotalBytes.toLong()) { "payload exceeds total size limit" }
    }
}

internal fun validateReplyImageManifest(
    replyType: ReplyType,
    imageSpecs: List<ReplyImagePartSpec>,
    policy: ReplyImagePolicy = ReplyImagePolicy(),
) {
    validateImagePayloadSizes(imageSpecs.map { it.byteLength }, policy = policy)
    imageSpecs.forEachIndexed { expectedIndex, imageSpec ->
        require(imageSpec.index == expectedIndex) { "metadata images must be ordered by index" }
        require(SHA256_HEX_PATTERN.matches(imageSpec.sha256Hex)) { "image sha256Hex must be 64 lowercase hex chars" }
        require(imageSpec.contentType.isNotBlank()) { "image contentType must not be blank" }
        require(imageSpec.contentType.lowercase() in policy.allowedContentTypes) { "image contentType is not allowed" }
    }
    if (replyType == ReplyType.IMAGE) {
        require(imageSpecs.size == 1) { "single image reply requires exactly one image" }
    }
}

private fun isPngSignature(imageBytes: ByteArray): Boolean =
    imageBytes.size >= 8 &&
        imageBytes[0] == 0x89.toByte() &&
        imageBytes[1] == 0x50.toByte() &&
        imageBytes[2] == 0x4E.toByte() &&
        imageBytes[3] == 0x47.toByte()

private fun isJpegSignature(imageBytes: ByteArray): Boolean =
    imageBytes.size >= 3 &&
        imageBytes[0] == 0xFF.toByte() &&
        imageBytes[1] == 0xD8.toByte() &&
        imageBytes[2] == 0xFF.toByte()

private fun isWebpSignature(imageBytes: ByteArray): Boolean =
    imageBytes.size >= 12 &&
        imageBytes[0] == 0x52.toByte() &&
        imageBytes[1] == 0x49.toByte() &&
        imageBytes[2] == 0x46.toByte() &&
        imageBytes[3] == 0x46.toByte() &&
        imageBytes[8] == 0x57.toByte() &&
        imageBytes[9] == 0x45.toByte() &&
        imageBytes[10] == 0x42.toByte() &&
        imageBytes[11] == 0x50.toByte()

private fun isGifSignature(imageBytes: ByteArray): Boolean =
    imageBytes.size >= 4 &&
        imageBytes[0] == 0x47.toByte() &&
        imageBytes[1] == 0x49.toByte() &&
        imageBytes[2] == 0x46.toByte() &&
        imageBytes[3] == 0x38.toByte()
