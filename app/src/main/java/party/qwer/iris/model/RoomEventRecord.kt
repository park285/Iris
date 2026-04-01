package party.qwer.iris.model

import kotlinx.serialization.Serializable

@Serializable
data class RoomEventRecord(
    val id: Long,
    val chatId: Long,
    val eventType: String,
    val userId: Long,
    val payload: String,
    val createdAt: Long,
)
