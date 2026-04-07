package party.qwer.iris.model

import kotlinx.serialization.Serializable

sealed interface RoomEvent {
    val type: String
    val chatId: Long
}

@Serializable
data class MemberEvent(
    override val type: String = "member_event",
    val event: String,
    override val chatId: Long,
    val linkId: Long?,
    val userId: Long,
    val nickname: String?,
    val estimated: Boolean = false,
    val timestamp: Long,
) : RoomEvent

@Serializable
data class NicknameChangeEvent(
    override val type: String = "nickname_change",
    override val chatId: Long,
    val linkId: Long?,
    val userId: Long,
    val oldNickname: String?,
    val newNickname: String?,
    val timestamp: Long,
) : RoomEvent

@Serializable
data class RoleChangeEvent(
    override val type: String = "role_change",
    override val chatId: Long,
    val linkId: Long?,
    val userId: Long,
    val oldRole: String,
    val newRole: String,
    val timestamp: Long,
) : RoomEvent

@Serializable
data class ProfileChangeEvent(
    override val type: String = "profile_change",
    override val chatId: Long,
    val linkId: Long?,
    val userId: Long,
    val timestamp: Long,
    val nickname: String? = null,
    val oldProfileImageUrl: String? = null,
    val newProfileImageUrl: String? = null,
) : RoomEvent
