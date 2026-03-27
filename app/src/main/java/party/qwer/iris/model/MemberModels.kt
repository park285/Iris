package party.qwer.iris.model

import kotlinx.serialization.Serializable

@Serializable
data class RoomListResponse(
    val rooms: List<RoomSummary>,
)

@Serializable
data class RoomSummary(
    val chatId: Long,
    val type: String?,
    val linkId: Long?,
    val activeMembersCount: Int?,
    val linkName: String? = null,
    val linkUrl: String? = null,
    val memberLimit: Int? = null,
    val searchable: Int? = null,
    val botRole: Int? = null,
)

@Serializable
data class MemberListResponse(
    val chatId: Long,
    val linkId: Long?,
    val members: List<MemberInfo>,
    val totalCount: Int,
)

@Serializable
data class MemberInfo(
    val userId: Long,
    val nickname: String?,
    val role: String,
    val roleCode: Int,
    val profileImageUrl: String? = null,
)

@Serializable
data class RoomInfoResponse(
    val chatId: Long,
    val type: String?,
    val linkId: Long?,
    val notices: List<NoticeInfo>,
    val blindedMemberIds: List<Long>,
    val botCommands: List<BotCommandInfo>,
    val openLink: OpenLinkInfo? = null,
)

@Serializable
data class NoticeInfo(
    val content: String,
    val authorId: Long,
    val updatedAt: Long,
)

@Serializable
data class BotCommandInfo(
    val name: String,
    val botId: Long,
)

@Serializable
data class OpenLinkInfo(
    val name: String?,
    val url: String?,
    val memberLimit: Int? = null,
    val description: String? = null,
    val searchable: Int? = null,
)

@Serializable
data class StatsResponse(
    val chatId: Long,
    val period: PeriodRange,
    val totalMessages: Int,
    val activeMembers: Int,
    val topMembers: List<MemberStats>,
)

@Serializable
data class PeriodRange(
    val from: Long,
    val to: Long,
)

@Serializable
data class MemberStats(
    val userId: Long,
    val nickname: String?,
    val messageCount: Int,
    val lastActiveAt: Long?,
    val messageTypes: Map<String, Int>,
)

@Serializable
data class MemberActivityResponse(
    val userId: Long,
    val nickname: String?,
    val messageCount: Int,
    val firstMessageAt: Long?,
    val lastMessageAt: Long?,
    val activeHours: List<Int>,
    val messageTypes: Map<String, Int>,
)

fun roleCodeToName(code: Int): String =
    when (code) {
        1 -> "owner"
        4 -> "admin"
        8 -> "bot"
        else -> "member"
    }
