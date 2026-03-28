package party.qwer.iris

internal data class DecodedImagePayload(
    val bytes: ByteArray,
)

internal data class ImagePayloadMetadata(
    val base64: String,
    val estimatedDecodedBytes: Int,
)

private val base64PayloadRegex = Regex("^[A-Za-z0-9+/]*={0,2}$")

internal fun validateImagePayloadMetadata(
    base64ImageDataStrings: List<String>,
    maxImagesPerRequest: Int = 8,
    maxTotalBytes: Int = 30 * 1024 * 1024,
): List<ImagePayloadMetadata> {
    require(base64ImageDataStrings.isNotEmpty()) { "no image data provided" }
    require(base64ImageDataStrings.size <= maxImagesPerRequest) { "too many images" }

    var totalBytes = 0
    return base64ImageDataStrings.map { base64 ->
        val normalized = base64.filterNot(Char::isWhitespace)
        require(normalized.isNotEmpty()) { "payload is empty" }
        require(normalized.length <= MAX_BASE64_IMAGE_PAYLOAD_LENGTH) { "payload exceeds size limit" }
        require(normalized.length % 4 == 0) { "invalid base64 payload" }
        require(base64PayloadRegex.matches(normalized)) { "invalid base64 payload" }

        val padding =
            when {
                normalized.endsWith("==") -> 2
                normalized.endsWith("=") -> 1
                else -> 0
            }
        val decodedSize = normalized.length / 4 * 3 - padding
        require(decodedSize in 1..MAX_IMAGE_PAYLOAD_BYTES) { "payload exceeds size limit" }
        totalBytes += decodedSize
        require(totalBytes <= maxTotalBytes) { "payload exceeds total size limit" }

        ImagePayloadMetadata(base64 = base64, estimatedDecodedBytes = decodedSize)
    }
}

internal fun validateImagePayloads(
    base64ImageDataStrings: List<String>,
    imageDecoder: (String) -> ByteArray = ::decodeBase64Image,
    maxImagesPerRequest: Int = 8,
    maxTotalBytes: Int = 30 * 1024 * 1024,
): List<DecodedImagePayload> {
    require(base64ImageDataStrings.isNotEmpty()) { "no image data provided" }
    require(base64ImageDataStrings.size <= maxImagesPerRequest) { "too many images" }

    var totalBytes = 0
    return base64ImageDataStrings.map { base64 ->
        require(base64.length <= MAX_BASE64_IMAGE_PAYLOAD_LENGTH) { "payload exceeds size limit" }
        val decodedBytes = imageDecoder(base64)
        require(decodedBytes.size <= MAX_IMAGE_PAYLOAD_BYTES) { "payload exceeds size limit" }
        totalBytes += decodedBytes.size
        require(totalBytes <= maxTotalBytes) { "payload exceeds total size limit" }
        DecodedImagePayload(bytes = decodedBytes)
    }
}
