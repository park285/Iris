package party.qwer.iris

import party.qwer.iris.model.MemberActivityResponse
import party.qwer.iris.model.MemberStats
import party.qwer.iris.model.PeriodRange
import party.qwer.iris.model.StatsResponse
import party.qwer.iris.storage.ChatId
import party.qwer.iris.storage.LinkId
import party.qwer.iris.storage.RoomStatsQueries
import party.qwer.iris.storage.UserId

internal class RoomStatisticsService(
    private val roomStats: RoomStatsQueries,
    private val metadata: MemberRepositoryMetadata,
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
        chatId: Long,
        period: String?,
        limit: Int,
        minMessages: Int = 0,
    ): StatsResponse {
        val periodSecs = metadata.parsePeriodSeconds(period)
        val now = nowEpochSeconds()
        val from = if (periodSecs != null) now - periodSecs else 0L
        val roomId = ChatId(chatId)
        val linkId = resolveLinkId(roomId)

        val rows = roomStats.loadTypeCountStats(roomId, from)
        val byUser = rows.groupBy { it.userId }
        val nicknameByUser = prepareNicknameLookup(byUser.keys, linkId, roomId)
        val memberStats =
            byUser
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
                    MemberStats(
                        userId = userId.value,
                        nickname = nicknameByUser[userId] ?: userId.value.toString(),
                        messageCount = total,
                        lastActiveAt = lastActive,
                        messageTypes = types,
                    )
                }.sortedByDescending { it.messageCount }
                .filter { it.messageCount >= minMessages }

        return StatsResponse(
            chatId = chatId,
            period = PeriodRange(from, now),
            totalMessages = memberStats.sumOf { it.messageCount },
            activeMembers = memberStats.size,
            topMembers = memberStats.take(limit),
        )
    }

    fun memberActivity(
        chatId: Long,
        userId: Long,
        period: String?,
    ): MemberActivityResponse {
        val periodSecs = metadata.parsePeriodSeconds(period)
        val now = nowEpochSeconds()
        val from = if (periodSecs != null) now - periodSecs else 0L
        val roomId = ChatId(chatId)
        val linkId = resolveLinkId(roomId)

        val rows = roomStats.loadMessageLog(roomId, UserId(userId), from)
        val types = mutableMapOf<String, Int>()
        val hours = IntArray(24)
        var firstAt: Long? = null
        var lastAt: Long? = null

        for (row in rows) {
            val ts = row.createdAt
            if (ts == 0L) continue
            if (firstAt == null) firstAt = ts
            lastAt = ts
            val hour = ((ts % 86400) / 3600).toInt()
            hours[hour]++
            val typeName = messageTypeNames[row.type] ?: "other"
            types[typeName] = (types[typeName] ?: 0) + 1
        }

        return MemberActivityResponse(
            userId = userId,
            nickname = resolveNickname(UserId(userId), linkId, roomId) ?: userId.toString(),
            messageCount = rows.size,
            firstMessageAt = firstAt,
            lastMessageAt = lastAt,
            activeHours = hours.toList(),
            messageTypes = types,
        )
    }
}
