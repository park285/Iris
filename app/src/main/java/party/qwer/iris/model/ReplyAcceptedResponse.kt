package party.qwer.iris.model

import kotlinx.serialization.Serializable

@Serializable
data class ReplyAcceptedResponse(
    val success: Boolean = true,
    val delivery: String = "queued",
    val requestId: String,
    val room: String,
    val type: ReplyType,
)
