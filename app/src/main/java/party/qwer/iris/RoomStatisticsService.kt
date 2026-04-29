package party.qwer.iris

import party.qwer.iris.model.MemberActivityResponse
import party.qwer.iris.model.MemberStats
import party.qwer.iris.model.PeriodRange
import party.qwer.iris.model.PeriodSpec
import party.qwer.iris.model.StatsResponse
import party.qwer.iris.nativecore.NativeCoreHolder
import party.qwer.iris.nativecore.NativeMemberActivityProjection
import party.qwer.iris.nativecore.NativeMemberStatsProjection
import party.qwer.iris.nativecore.NativeRoomStatsProjection
import party.qwer.iris.nativecore.NativeStatisticsProjectionBatchItem
import party.qwer.iris.nativecore.NativeStatisticsProjectionRow
import party.qwer.iris.nativecore.nativeStatisticsKindMemberActivity
import party.qwer.iris.nativecore.nativeStatisticsKindRoomStats
import party.qwer.iris.storage.ChatId
import party.qwer.iris.storage.LinkId
import party.qwer.iris.storage.MessageLogRow
import party.qwer.iris.storage.MessageTypeCountRow
import party.qwer.iris.storage.RoomStatsQueries
import party.qwer.iris.storage.UserId

internal class RoomStatisticsService(
    private val roomStats: RoomStatsQueries,
    private val resolveLinkId: (ChatId) -> LinkId?,
    private val prepareNicknameLookup: (
        userIds: Collection<UserId>,
        linkId: LinkId?,
        chatId: ChatId?,
    ) -> Map<UserId, String>,
    private val resolveNickname: (
        userId: UserId,
        linkId: LinkId?,
        chatId: ChatId?,
    ) -> String?,
    private val messageTypeNames: Map<String, String>,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    fun roomStats(
        chatId: ChatId,
        period: PeriodSpec,
        limit: Int,
        minMessages: Int = 0,
    ): StatsResponse {
        val periodSecs = PeriodSpecParser().toSeconds(period)
        val now = nowEpochSeconds()
        val from = if (periodSecs != null) now - periodSecs else 0L
        val linkId = resolveLinkId(chatId)

        val rows = roomStats.loadTypeCountStats(chatId, from)
        val nicknameByUser = prepareNicknameLookup(rows.map { it.userId }.toSet(), linkId, chatId)
        val projectionItem =
            NativeStatisticsProjectionBatchItem(
                kind = nativeStatisticsKindRoomStats,
                rows = rows.map { it.toNativeStatisticsProjectionRow() },
                messageTypeNames = messageTypeNames,
                nicknames = nicknameByUser.mapKeys { (userId, _) -> userId.value.toString() },
                limit = limit,
                minMessages = minMessages,
            )
        val projection =
            NativeCoreHolder.current().projectRoomStatsOrFallback(projectionItem) {
                projectRoomStatsKotlin(
                    rows = rows,
                    nicknameByUser = nicknameByUser,
                    messageTypeNames = messageTypeNames,
                    limit = limit,
                    minMessages = minMessages,
                )
            }

        return StatsResponse(
            chatId = chatId.value,
            period = PeriodRange(from, now),
            totalMessages = projection.totalMessages,
            activeMembers = projection.activeMembers,
            topMembers = projection.memberStats.map { it.toMemberStats() },
        )
    }

    fun memberActivity(
        chatId: ChatId,
        userId: UserId,
        period: PeriodSpec,
    ): MemberActivityResponse {
        val periodSecs = PeriodSpecParser().toSeconds(period)
        val now = nowEpochSeconds()
        val from = if (periodSecs != null) now - periodSecs else 0L
        val linkId = resolveLinkId(chatId)

        val rows = roomStats.loadMessageLog(chatId, userId, from)
        val projectionItem =
            NativeStatisticsProjectionBatchItem(
                kind = nativeStatisticsKindMemberActivity,
                rows = rows.map { it.toNativeStatisticsProjectionRow() },
                messageTypeNames = messageTypeNames,
            )
        val projection =
            NativeCoreHolder.current().projectMemberActivityOrFallback(projectionItem) {
                projectMemberActivityKotlin(rows = rows, messageTypeNames = messageTypeNames)
            }

        return MemberActivityResponse(
            userId = userId.value,
            nickname = resolveNickname(userId, linkId, chatId) ?: userId.value.toString(),
            messageCount = projection.messageCount,
            firstMessageAt = projection.firstMessageAt,
            lastMessageAt = projection.lastMessageAt,
            activeHours = projection.activeHours,
            messageTypes = projection.messageTypes,
        )
    }
}

internal fun projectRoomStatsKotlin(
    rows: List<MessageTypeCountRow>,
    nicknameByUser: Map<UserId, String>,
    messageTypeNames: Map<String, String>,
    limit: Int,
    minMessages: Int,
): NativeRoomStatsProjection {
    val memberStats =
        rows
            .groupBy { it.userId }
            .map { (userId, typeRows) ->
                val types = mutableMapOf<String, Int>()
                var total = 0
                var lastActive: Long? = null
                for (row in typeRows) {
                    val typeName = messageTypeNames[row.type] ?: "other"
                    types[typeName] = (types[typeName] ?: 0) + row.count
                    total += row.count
                    val lastSeen = row.lastActive
                    if (lastSeen != null && (lastActive == null || lastSeen > lastActive)) {
                        lastActive = lastSeen
                    }
                }
                NativeMemberStatsProjection(
                    userId = userId.value,
                    nickname = nicknameByUser[userId] ?: userId.value.toString(),
                    messageCount = total,
                    lastActiveAt = lastActive,
                    messageTypes = types,
                )
            }.sortedByDescending { it.messageCount }
            .filter { it.messageCount >= minMessages }

    return NativeRoomStatsProjection(
        memberStats = memberStats.take(limit),
        totalMessages = memberStats.sumOf { it.messageCount },
        activeMembers = memberStats.size,
    )
}

internal fun projectMemberActivityKotlin(
    rows: List<MessageLogRow>,
    messageTypeNames: Map<String, String>,
): NativeMemberActivityProjection {
    val types = mutableMapOf<String, Int>()
    val hours = IntArray(24)
    var firstAt: Long? = null
    var lastAt: Long? = null

    for (row in rows) {
        val ts = row.createdAt
        if (ts == 0L) continue
        if (firstAt == null) firstAt = ts
        lastAt = ts
        val hour = ((ts % 86_400) / 3_600).toInt()
        hours[hour]++
        val typeName = messageTypeNames[row.type] ?: "other"
        types[typeName] = (types[typeName] ?: 0) + 1
    }

    return NativeMemberActivityProjection(
        messageCount = rows.size,
        firstMessageAt = firstAt,
        lastMessageAt = lastAt,
        activeHours = hours.toList(),
        messageTypes = types,
    )
}

private fun MessageTypeCountRow.toNativeStatisticsProjectionRow(): NativeStatisticsProjectionRow =
    NativeStatisticsProjectionRow(
        userId = userId.value,
        type = type,
        count = count,
        lastActive = lastActive,
    )

private fun MessageLogRow.toNativeStatisticsProjectionRow(): NativeStatisticsProjectionRow =
    NativeStatisticsProjectionRow(
        type = type,
        createdAt = createdAt,
    )

private fun NativeMemberStatsProjection.toMemberStats(): MemberStats =
    MemberStats(
        userId = userId,
        nickname = nickname,
        messageCount = messageCount,
        lastActiveAt = lastActiveAt,
        messageTypes = messageTypes,
    )
