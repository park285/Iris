package party.qwer.iris.model

import kotlinx.serialization.Serializable

@Serializable
data class ReplyImageMetadata(
    val type: ReplyType = ReplyType.IMAGE,
    val room: String,
    val threadId: String? = null,
    val threadScope: Int? = null,
    val images: List<ReplyImagePartSpec>,
)

@Serializable
data class ReplyImagePartSpec(
    val index: Int,
    val sha256Hex: String,
    val byteLength: Long,
    val contentType: String,
)
