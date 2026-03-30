package party.qwer.iris

internal fun validateImageBytesPayload(
    imageBytesList: List<ByteArray>,
    maxImagesPerRequest: Int = 8,
    maxTotalBytes: Int = 30 * 1024 * 1024,
    maxSingleImageBytes: Int = MAX_IMAGE_PAYLOAD_BYTES,
): List<ByteArray> {
    require(imageBytesList.isNotEmpty()) { "no image data provided" }
    require(imageBytesList.size <= maxImagesPerRequest) { "too many images" }

    var totalBytes = 0
    imageBytesList.forEach { bytes ->
        require(bytes.isNotEmpty()) { "empty image data" }
        require(bytes.size <= maxSingleImageBytes) { "payload exceeds size limit" }
        totalBytes += bytes.size
        require(totalBytes <= maxTotalBytes) { "payload exceeds total size limit" }
    }
    return imageBytesList
}
