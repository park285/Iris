package party.qwer.iris.model

import kotlinx.serialization.Serializable

@Serializable
data class ReplyImageMetadata(
    val type: ReplyType = ReplyType.IMAGE,
    val room: String,
    val threadId: String? = null,
    val threadScope: Int? = null,
)
