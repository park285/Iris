package party.qwer.iris.model

import kotlinx.serialization.Serializable

@Serializable
data class MemberEvent(
    val type: String = "member_event",
    val event: String,
    val chatId: Long,
    val linkId: Long?,
    val userId: Long,
    val nickname: String?,
    val estimated: Boolean = false,
    val timestamp: Long,
)

@Serializable
data class NicknameChangeEvent(
    val type: String = "nickname_change",
    val chatId: Long,
    val linkId: Long?,
    val userId: Long,
    val oldNickname: String?,
    val newNickname: String?,
    val timestamp: Long,
)

@Serializable
data class RoleChangeEvent(
    val type: String = "role_change",
    val chatId: Long,
    val linkId: Long?,
    val userId: Long,
    val oldRole: String,
    val newRole: String,
    val timestamp: Long,
)

@Serializable
data class ProfileChangeEvent(
    val type: String = "profile_change",
    val chatId: Long,
    val linkId: Long?,
    val userId: Long,
    val timestamp: Long,
)
