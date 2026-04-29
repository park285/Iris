package party.qwer.iris.nativecore

import kotlinx.serialization.Serializable

@Serializable
internal data class NativeStatisticsProjectionBatchRequest(
    val items: List<NativeStatisticsProjectionBatchItem>,
)

@Serializable
internal data class NativeStatisticsProjectionBatchItem(
    val kind: String,
    val rows: List<NativeStatisticsProjectionRow> = emptyList(),
    val messageTypeNames: Map<String, String> = emptyMap(),
    val nicknames: Map<String, String> = emptyMap(),
    val limit: Int = 0,
    val minMessages: Int = 0,
)

@Serializable
internal data class NativeStatisticsProjectionRow(
    val userId: Long? = null,
    val type: String? = null,
    val count: Int? = null,
    val lastActive: Long? = null,
    val createdAt: Long? = null,
)

@Serializable
internal data class NativeStatisticsProjectionBatchResponse(
    val items: List<NativeStatisticsProjectionBatchResult> = emptyList(),
)

@Serializable
internal data class NativeStatisticsProjectionBatchResult(
    val kind: String = "",
    val ok: Boolean = false,
    val memberStats: List<NativeMemberStatsProjection>? = null,
    val totalMessages: Int? = null,
    val activeMembers: Int? = null,
    val messageCount: Int? = null,
    val firstMessageAt: Long? = null,
    val lastMessageAt: Long? = null,
    val activeHours: List<Int>? = null,
    val messageTypes: Map<String, Int>? = null,
    val errorKind: String? = null,
    val error: String? = null,
)

@Serializable
internal data class NativeRoomStatsProjection(
    val memberStats: List<NativeMemberStatsProjection> = emptyList(),
    val totalMessages: Int = 0,
    val activeMembers: Int = 0,
)

@Serializable
internal data class NativeMemberStatsProjection(
    val userId: Long,
    val nickname: String,
    val messageCount: Int,
    val lastActiveAt: Long? = null,
    val messageTypes: Map<String, Int>,
)

@Serializable
internal data class NativeMemberActivityProjection(
    val messageCount: Int,
    val firstMessageAt: Long? = null,
    val lastMessageAt: Long? = null,
    val activeHours: List<Int> = emptyList(),
    val messageTypes: Map<String, Int> = emptyMap(),
)

internal const val nativeStatisticsKindRoomStats = "roomStats"
internal const val nativeStatisticsKindMemberActivity = "memberActivity"
