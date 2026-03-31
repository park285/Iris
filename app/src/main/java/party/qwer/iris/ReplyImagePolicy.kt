package party.qwer.iris

internal data class ReplyImagePolicy(
    val maxImagesPerRequest: Int = MAX_REPLY_IMAGE_COUNT,
    val maxTotalBytes: Int = MAX_TOTAL_REPLY_IMAGE_BYTES,
    val maxSingleImageBytes: Int = MAX_IMAGE_PAYLOAD_BYTES,
    val maxMetadataBytes: Int = MAX_REPLY_IMAGE_METADATA_BYTES,
    val jsonReplyBodyMaxBytes: Int = 256 * 1024,
    val allowedContentTypes: Set<String> =
        setOf(
            "image/png",
            "image/jpeg",
            "image/webp",
            "image/gif",
        ),
)
