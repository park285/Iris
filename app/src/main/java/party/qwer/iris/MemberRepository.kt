package party.qwer.iris

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import party.qwer.iris.model.BotCommandInfo
import party.qwer.iris.model.MemberActivityResponse
import party.qwer.iris.model.MemberInfo
import party.qwer.iris.model.MemberListResponse
import party.qwer.iris.model.MemberStats
import party.qwer.iris.model.NoticeInfo
import party.qwer.iris.model.OpenLinkInfo
import party.qwer.iris.model.PeriodRange
import party.qwer.iris.model.RoomInfoResponse
import party.qwer.iris.model.RoomListResponse
import party.qwer.iris.model.RoomSummary
import party.qwer.iris.model.StatsResponse
import party.qwer.iris.model.roleCodeToName

class MemberRepository(
    private val executeQuery: (String, Array<String?>?, Int) -> List<Map<String, String?>>,
    private val decrypt: (Int, String, Long) -> String,
    private val botId: Long,
) {
    companion object {
        private const val MAX_ROWS = 500
        private const val STATS_ROW_LIMIT = 50_000
        private val json = Json { ignoreUnknownKeys = true }

        private val MESSAGE_TYPE_NAMES =
            mapOf(
                "0" to "text",
                "1" to "photo",
                "2" to "video",
                "3" to "voice",
                "12" to "file",
                "20" to "emoticon",
                "26" to "reply",
                "27" to "multi_photo",
            )
    }

    fun listRooms(): RoomListResponse {
        val rows =
            executeQuery(
                """
                SELECT cr.id, cr.type, cr.active_members_count, cr.link_id,
                       ol.name AS link_name, ol.url AS link_url, ol.member_limit, ol.searchable,
                       op.link_member_type AS bot_role
                FROM chat_rooms cr
                LEFT JOIN db2.open_link ol ON cr.link_id = ol.id
                LEFT JOIN db2.open_profile op ON cr.link_id = op.link_id
                WHERE cr.type LIKE 'O%' AND cr.link_id > 0
                """.trimIndent(),
                null,
                MAX_ROWS,
            )
        return RoomListResponse(
            rooms =
                rows.map { row ->
                    RoomSummary(
                        chatId = row["id"]?.toLongOrNull() ?: 0L,
                        type = row["type"],
                        linkId = row["link_id"]?.toLongOrNull(),
                        activeMembersCount = row["active_members_count"]?.toIntOrNull(),
                        linkName = row["link_name"],
                        linkUrl = row["link_url"],
                        memberLimit = row["member_limit"]?.toIntOrNull(),
                        searchable = row["searchable"]?.toIntOrNull(),
                        botRole = row["bot_role"]?.toIntOrNull(),
                    )
                },
        )
    }

    fun listMembers(chatId: Long): MemberListResponse {
        val linkIdRow =
            executeQuery(
                "SELECT link_id FROM chat_rooms WHERE id = ?",
                arrayOf(chatId.toString()),
                1,
            ).firstOrNull()
        val linkId =
            linkIdRow?.get("link_id")?.toLongOrNull()
                ?: return MemberListResponse(chatId, null, emptyList(), 0)

        val rows =
            executeQuery(
                """
                SELECT user_id, nickname, link_member_type, profile_image_url, enc
                FROM db2.open_chat_member WHERE link_id = ?
                """.trimIndent(),
                arrayOf(linkId.toString()),
                MAX_ROWS,
            )
        val members =
            rows.map { row ->
                val enc = row["enc"]?.toIntOrNull() ?: 0
                val rawNick = row["nickname"]
                val roleCode = row["link_member_type"]?.toIntOrNull() ?: 2
                MemberInfo(
                    userId = row["user_id"]?.toLongOrNull() ?: 0L,
                    nickname = if (rawNick != null && enc > 0) decrypt(enc, rawNick, botId) else rawNick,
                    role = roleCodeToName(roleCode),
                    roleCode = roleCode,
                    profileImageUrl = row["profile_image_url"],
                )
            }
        return MemberListResponse(chatId, linkId, members, members.size)
    }

    fun roomInfo(chatId: Long): RoomInfoResponse {
        val roomRow =
            executeQuery(
                "SELECT id, type, link_id, meta, blinded_member_ids FROM chat_rooms WHERE id = ?",
                arrayOf(chatId.toString()),
                1,
            ).firstOrNull() ?: return RoomInfoResponse(chatId, null, null, emptyList(), emptyList(), emptyList())

        val linkId = roomRow["link_id"]?.toLongOrNull()
        val notices = parseNotices(roomRow["meta"])
        val blindedIds = parseJsonLongArray(roomRow["blinded_member_ids"]).toList()

        val botCommands =
            if (linkId != null) {
                executeQuery(
                    "SELECT name, bot_id FROM db2.openchat_bot_command WHERE link_id = ?",
                    arrayOf(linkId.toString()),
                    MAX_ROWS,
                ).map { BotCommandInfo(it["name"] ?: "", it["bot_id"]?.toLongOrNull() ?: 0L) }
            } else {
                emptyList()
            }

        val openLink =
            if (linkId != null) {
                executeQuery(
                    "SELECT name, url, member_limit, description, searchable FROM db2.open_link WHERE id = ?",
                    arrayOf(linkId.toString()),
                    1,
                ).firstOrNull()?.let { row ->
                    OpenLinkInfo(
                        name = row["name"],
                        url = row["url"],
                        memberLimit = row["member_limit"]?.toIntOrNull(),
                        description = row["description"],
                        searchable = row["searchable"]?.toIntOrNull(),
                    )
                }
            } else {
                null
            }

        return RoomInfoResponse(
            chatId = chatId,
            type = roomRow["type"],
            linkId = linkId,
            notices = notices,
            blindedMemberIds = blindedIds,
            botCommands = botCommands,
            openLink = openLink,
        )
    }

    fun roomStats(
        chatId: Long,
        period: String?,
        limit: Int,
    ): StatsResponse {
        val periodSecs = parsePeriodSeconds(period)
        val now = System.currentTimeMillis() / 1000
        val from = if (periodSecs != null) now - periodSecs else 0L

        val rows =
            executeQuery(
                """
                SELECT user_id, type, COUNT(*) as cnt, MAX(created_at) as last_active
                FROM chat_logs WHERE chat_id = ? AND created_at >= ?
                GROUP BY user_id, type ORDER BY cnt DESC LIMIT ?
                """.trimIndent(),
                arrayOf(chatId.toString(), from.toString(), STATS_ROW_LIMIT.toString()),
                STATS_ROW_LIMIT,
            )

        val byUser = rows.groupBy { it["user_id"]?.toLongOrNull() ?: 0L }
        val memberStats =
            byUser
                .map { (userId, typeRows) ->
                    val types = mutableMapOf<String, Int>()
                    var total = 0
                    var lastActive: Long? = null
                    for (row in typeRows) {
                        val typeName = MESSAGE_TYPE_NAMES[row["type"]] ?: "other"
                        val cnt = row["cnt"]?.toIntOrNull() ?: 0
                        types[typeName] = (types[typeName] ?: 0) + cnt
                        total += cnt
                        val la = row["last_active"]?.toLongOrNull()
                        if (la != null && (lastActive == null || la > lastActive)) lastActive = la
                    }
                    MemberStats(userId, resolveNickname(userId), total, lastActive, types)
                }.sortedByDescending { it.messageCount }

        val totalMessages = memberStats.sumOf { it.messageCount }
        val activeMembers = memberStats.size
        val topMembers = memberStats.take(limit)

        return StatsResponse(
            chatId = chatId,
            period = PeriodRange(from, now),
            totalMessages = totalMessages,
            activeMembers = activeMembers,
            topMembers = topMembers,
        )
    }

    fun memberActivity(
        chatId: Long,
        userId: Long,
        period: String?,
    ): MemberActivityResponse {
        val periodSecs = parsePeriodSeconds(period)
        val now = System.currentTimeMillis() / 1000
        val from = if (periodSecs != null) now - periodSecs else 0L

        val rows =
            executeQuery(
                """
                SELECT type, created_at FROM chat_logs
                WHERE chat_id = ? AND user_id = ? AND created_at >= ?
                ORDER BY created_at ASC LIMIT ?
                """.trimIndent(),
                arrayOf(chatId.toString(), userId.toString(), from.toString(), STATS_ROW_LIMIT.toString()),
                STATS_ROW_LIMIT,
            )

        val types = mutableMapOf<String, Int>()
        val hours = IntArray(24)
        var firstAt: Long? = null
        var lastAt: Long? = null

        for (row in rows) {
            val ts = row["created_at"]?.toLongOrNull() ?: continue
            if (firstAt == null) firstAt = ts
            lastAt = ts
            val hour = ((ts % 86400) / 3600).toInt()
            hours[hour]++
            val typeName = MESSAGE_TYPE_NAMES[row["type"]] ?: "other"
            types[typeName] = (types[typeName] ?: 0) + 1
        }

        return MemberActivityResponse(
            userId = userId,
            nickname = resolveNickname(userId),
            messageCount = rows.size,
            firstMessageAt = firstAt,
            lastMessageAt = lastAt,
            activeHours = hours.toList(),
            messageTypes = types,
        )
    }

    private fun resolveNickname(userId: Long): String? {
        val row =
            executeQuery(
                "SELECT nickname, enc FROM db2.open_chat_member WHERE user_id = ? LIMIT 1",
                arrayOf(userId.toString()),
                1,
            ).firstOrNull() ?: return null
        val enc = row["enc"]?.toIntOrNull() ?: 0
        val rawNick = row["nickname"] ?: return null
        return if (enc > 0) decrypt(enc, rawNick, botId) else rawNick
    }

    fun snapshot(chatId: Long): RoomSnapshotData {
        val roomRow =
            executeQuery(
                "SELECT members, blinded_member_ids, link_id FROM chat_rooms WHERE id = ?",
                arrayOf(chatId.toString()),
                1,
            ).firstOrNull()

        val linkId = roomRow?.get("link_id")?.toLongOrNull()
        val memberIds = parseJsonLongArray(roomRow?.get("members"))
        val blindedIds = parseJsonLongArray(roomRow?.get("blinded_member_ids"))

        val memberDetails =
            if (linkId != null) {
                executeQuery(
                    "SELECT user_id, nickname, link_member_type, profile_image_url, enc FROM db2.open_chat_member WHERE link_id = ?",
                    arrayOf(linkId.toString()),
                    MAX_ROWS,
                )
            } else {
                emptyList()
            }

        val nicknames = mutableMapOf<Long, String>()
        val roles = mutableMapOf<Long, Int>()
        val profileImages = mutableMapOf<Long, String>()

        for (row in memberDetails) {
            val uid = row["user_id"]?.toLongOrNull() ?: continue
            val enc = row["enc"]?.toIntOrNull() ?: 0
            val rawNick = row["nickname"]
            nicknames[uid] = if (rawNick != null && enc > 0) decrypt(enc, rawNick, botId) else rawNick ?: ""
            roles[uid] = row["link_member_type"]?.toIntOrNull() ?: 2
            row["profile_image_url"]?.let { profileImages[uid] = it }
        }

        return RoomSnapshotData(
            chatId = chatId,
            linkId = linkId,
            memberIds = memberIds,
            blindedIds = blindedIds,
            nicknames = nicknames,
            roles = roles,
            profileImages = profileImages,
        )
    }

    fun parseJsonLongArray(raw: String?): Set<Long> {
        if (raw.isNullOrBlank() || raw == "[]") return emptySet()
        return try {
            json
                .parseToJsonElement(raw)
                .jsonArray
                .mapNotNull {
                    it.jsonPrimitive.long
                }.toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    fun parsePeriodSeconds(period: String?): Long? =
        when {
            period == "all" -> null
            period != null && period.endsWith("d") ->
                period.dropLast(1).toLongOrNull()?.times(86400) ?: (7 * 86400L)
            else -> 7 * 86400L
        }

    fun resolveSenderRole(
        userId: Long,
        linkId: Long?,
    ): Int? {
        if (linkId == null) return null
        val row =
            executeQuery(
                "SELECT link_member_type FROM db2.open_chat_member WHERE user_id = ? AND link_id = ? LIMIT 1",
                arrayOf(userId.toString(), linkId.toString()),
                1,
            ).firstOrNull() ?: return null
        return row["link_member_type"]?.toIntOrNull()
    }

    private fun parseNotices(meta: String?): List<NoticeInfo> {
        if (meta.isNullOrBlank()) return emptyList()
        return try {
            json.parseToJsonElement(meta)
            emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }
}

data class RoomSnapshotData(
    val chatId: Long,
    val linkId: Long?,
    val memberIds: Set<Long>,
    val blindedIds: Set<Long>,
    val nicknames: Map<Long, String>,
    val roles: Map<Long, Int>,
    val profileImages: Map<Long, String>,
)
