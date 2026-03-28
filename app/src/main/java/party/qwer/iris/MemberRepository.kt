package party.qwer.iris

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
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
    private val learnObservedProfileUserMappings: (Long, Map<Long, String>) -> Unit = { _, _ -> },
) {
    private data class ObservedProfileHint(
        val displayName: String?,
        val roomName: String?,
    )

    private data class MemberActivityCacheEntry(
        val memberIds: Set<Long>,
        val cachedAtMs: Long,
        val activityByUser: Map<Long, Map<String, String?>>,
    )

    companion object {
        private const val MAX_ROWS = 2000
        private const val STATS_ROW_LIMIT = 50_000
        private const val MEMBER_ACTIVITY_CACHE_TTL_MS = 30_000L
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

    private val memberActivityCache = mutableMapOf<Long, MemberActivityCacheEntry>()

    fun listRooms(): RoomListResponse {
        val rows =
            executeQuery(
                """
                SELECT cr.id, cr.type, cr.active_members_count, cr.link_id, cr.meta, cr.members,
                       ol.name AS link_name, ol.url AS link_url, ol.member_limit, ol.searchable,
                       op.link_member_type AS bot_role
                FROM chat_rooms cr
                LEFT JOIN db2.open_link ol ON cr.link_id = ol.id
                LEFT JOIN db2.open_profile op ON cr.link_id = op.link_id
                WHERE (cr.type LIKE 'O%' AND cr.link_id > 0) OR cr.type IN ('MultiChat', 'DirectChat')
                """.trimIndent(),
                null,
                MAX_ROWS,
            )
        val rooms =
            rows.map { row ->
                RoomSummary(
                    chatId = row["id"]?.toLongOrNull() ?: 0L,
                    type = row["type"],
                    linkId = row["link_id"]?.toLongOrNull(),
                    activeMembersCount = row["active_members_count"]?.toIntOrNull(),
                    linkName =
                        row["link_name"]
                            ?: resolveNonOpenRoomName(
                                chatId = row["id"]?.toLongOrNull() ?: 0L,
                                roomType = row["type"],
                                meta = row["meta"],
                                members = row["members"],
                            ),
                    linkUrl = row["link_url"],
                    memberLimit = row["member_limit"]?.toIntOrNull(),
                    searchable = row["searchable"]?.toIntOrNull(),
                    botRole = row["bot_role"]?.toIntOrNull(),
                )
            }
        return RoomListResponse(
            rooms =
                rooms
                    .groupBy { it.linkId ?: it.chatId }
                    .values
                    .map { group ->
                        group.maxWithOrNull(
                            compareBy<RoomSummary>(
                                { if (it.chatId > 0) 1 else 0 },
                                { it.activeMembersCount ?: 0 },
                                { it.chatId },
                            ),
                        ) ?: group.first()
                    },
        )
    }

    fun listMembers(chatId: Long): MemberListResponse {
        val roomRow =
            executeQuery(
                "SELECT link_id, type, members, active_members_count FROM chat_rooms WHERE id = ?",
                arrayOf(chatId.toString()),
                1,
            ).firstOrNull()
        val linkId =
            roomRow?.get("link_id")?.toLongOrNull()
        val roomType = roomRow?.get("type").orEmpty()
        if (roomRow == null) {
            return MemberListResponse(chatId, null, emptyList(), 0)
        }

        if (linkId != null) {
            val rows =
                executeQuery(
                    """
                    SELECT user_id, nickname, link_member_type, profile_image_url, enc
                    FROM db2.open_chat_member WHERE link_id = ?
                    """.trimIndent(),
                    arrayOf(linkId.toString()),
                    MAX_ROWS,
                )
            val memberIds =
                rows
                    .mapNotNull { it["user_id"]?.toLongOrNull() }
                    .distinct()
            val activityByUser = loadMemberActivityByUser(chatId, memberIds)
            val members =
                rows.map { row ->
                    val enc = row["enc"]?.toIntOrNull() ?: 0
                    val rawNick = row["nickname"]
                    val roleCode = row["link_member_type"]?.toIntOrNull() ?: 2
                    val userId = row["user_id"]?.toLongOrNull() ?: 0L
                    val activity = activityByUser[userId]
                    MemberInfo(
                        userId = userId,
                        nickname = if (rawNick != null && enc > 0) decrypt(enc, rawNick, botId) else rawNick,
                        role = roleCodeToName(roleCode),
                        roleCode = roleCode,
                        profileImageUrl = row["profile_image_url"],
                        messageCount = activity?.get("message_count")?.toIntOrNull() ?: 0,
                        lastActiveAt = activity?.get("last_active")?.toLongOrNull(),
                    )
                }
            learnObservedProfileMappings(chatId, members)
            return MemberListResponse(chatId, linkId, members, members.size)
        }

        val roomMemberIds = parseJsonLongArray(roomRow["members"])
        val scopedMemberIds =
            buildSet {
                addAll(roomMemberIds)
                if (botId > 0L) {
                    add(botId)
                }
            }.toList()
        val activityByUser =
            if (scopedMemberIds.isNotEmpty()) {
                loadMemberActivityByUser(chatId, scopedMemberIds)
            } else {
                executeQuery(
                    """
                    SELECT user_id, COUNT(*) as message_count, MAX(created_at) as last_active
                    FROM chat_logs
                    WHERE chat_id = ?
                    GROUP BY user_id
                    ORDER BY last_active DESC
                    LIMIT ?
                    """.trimIndent(),
                    arrayOf(chatId.toString(), STATS_ROW_LIMIT.toString()),
                    STATS_ROW_LIMIT,
                ).associateBy { it["user_id"]?.toLongOrNull() ?: 0L }
            }
        val userIds =
            ((if (scopedMemberIds.isNotEmpty()) scopedMemberIds else activityByUser.keys.toList()) + activityByUser.keys)
                .sortedByDescending { activityByUser[it]?.get("last_active")?.toLongOrNull() ?: Long.MIN_VALUE }
                .distinct()
        val directChatParticipantId =
            if (roomType == "DirectChat") {
                userIds.filter { it != botId }.distinct().singleOrNull()
            } else {
                null
            }
        val observedProfile = resolveObservedProfileByChatId(chatId)
        val members =
            userIds.map { userId ->
                val roleCode = if (userId == botId) 8 else 2
                val activity = activityByUser[userId]
                val resolvedNickname = resolveNickname(userId, chatId = chatId)
                val nickname =
                    if (
                        roomType == "DirectChat" &&
                        userId == directChatParticipantId &&
                        resolvedNickname == userId.toString()
                    ) {
                        observedProfile?.displayName ?: resolvedNickname
                    } else {
                        resolvedNickname
                    }
                MemberInfo(
                    userId = userId,
                    nickname = nickname,
                    role = if (userId == botId) "bot" else roleCodeToName(roleCode),
                    roleCode = roleCode,
                    profileImageUrl = null,
                    messageCount = activity?.get("message_count")?.toIntOrNull() ?: 0,
                    lastActiveAt = activity?.get("last_active")?.toLongOrNull(),
                )
            }
        learnObservedProfileMappings(chatId, members)
        val totalCount = roomRow["active_members_count"]?.toIntOrNull() ?: members.size
        return MemberListResponse(chatId, null, members, maxOf(totalCount, members.size))
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

    fun resolveDisplayName(
        userId: Long,
        chatId: Long,
        linkId: Long? = resolveLinkId(chatId),
    ): String = resolveNickname(userId, linkId = linkId, chatId = chatId) ?: userId.toString()

    fun roomStats(
        chatId: Long,
        period: String?,
        limit: Int,
        minMessages: Int = 0,
    ): StatsResponse {
        val periodSecs = parsePeriodSeconds(period)
        val now = System.currentTimeMillis() / 1000
        val from = if (periodSecs != null) now - periodSecs else 0L
        val linkId = resolveLinkId(chatId)

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
        val nicknameByUser =
            if (linkId != null) {
                loadOpenNicknamesByUserIds(linkId, byUser.keys.toList())
            } else {
                emptyMap()
            }
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
                    MemberStats(
                        userId,
                        nicknameByUser[userId] ?: resolveNickname(userId, linkId),
                        total,
                        lastActive,
                        types,
                    )
                }.sortedByDescending { it.messageCount }
                .filter { it.messageCount >= minMessages }

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
        val linkId = resolveLinkId(chatId)

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
            nickname = resolveNickname(userId, linkId),
            messageCount = rows.size,
            firstMessageAt = firstAt,
            lastMessageAt = lastAt,
            activeHours = hours.toList(),
            messageTypes = types,
        )
    }

    private fun resolveNickname(
        userId: Long,
        linkId: Long? = null,
        chatId: Long? = null,
    ): String? {
        if (linkId != null) {
            val row =
                executeQuery(
                    "SELECT nickname, enc FROM db2.open_chat_member WHERE user_id = ? AND link_id = ? LIMIT 1",
                    arrayOf(userId.toString(), linkId.toString()),
                    1,
                ).firstOrNull()
            val enc = row?.get("enc")?.toIntOrNull() ?: 0
            val rawNick = row?.get("nickname")
            if (rawNick != null) {
                return if (enc > 0) decrypt(enc, rawNick, botId) else rawNick
            }
        }

        val friendRow =
            executeQuery(
                "SELECT name, enc FROM db2.friends WHERE id = ? LIMIT 1",
                arrayOf(userId.toString()),
                1,
            ).firstOrNull()
        if (friendRow == null) {
            return resolveObservedDisplayName(userId, chatId) ?: userId.toString()
        }
        val enc = friendRow["enc"]?.toIntOrNull() ?: 0
        val rawName = friendRow["name"] ?: return resolveObservedDisplayName(userId, chatId) ?: userId.toString()
        return if (enc > 0) decrypt(enc, rawName, botId) else rawName
    }

    private fun resolveObservedDisplayName(
        userId: Long,
        chatId: Long?,
    ): String? = resolveObservedDisplayNamesBatch(listOf(userId), chatId)[userId]

    private fun resolveObservedDisplayNamesBatch(
        userIds: Collection<Long>,
        chatId: Long?,
    ): Map<Long, String> {
        val orderedIds = userIds.distinct().filter { it > 0L }
        if (orderedIds.isEmpty()) return emptyMap()

        return try {
            val placeholders = orderedIds.joinToString(",") { "?" }
            val querySpec =
                if (chatId != null) {
                    """
                    SELECT user_id, display_name
                    FROM db3.observed_profile_user_links
                    WHERE chat_id = ? AND user_id IN ($placeholders)
                    ORDER BY user_id ASC, updated_at DESC
                    """.trimIndent() to
                        buildList<String?> {
                            add(chatId.toString())
                            addAll(orderedIds.map { it.toString() })
                        }.toTypedArray<String?>()
                } else {
                    """
                    SELECT user_id, display_name
                    FROM db3.observed_profile_user_links
                    WHERE user_id IN ($placeholders)
                    ORDER BY user_id ASC, updated_at DESC
                    """.trimIndent() to
                        orderedIds.map { it.toString() }.toTypedArray<String?>()
                }
            val sql = querySpec.first
            val bindArgs = querySpec.second

            val result = LinkedHashMap<Long, String>()
            executeQuery(sql, bindArgs, orderedIds.size).forEach { row ->
                val userId = row["user_id"]?.toLongOrNull() ?: return@forEach
                val displayName = row["display_name"]?.takeIf { it.isNotBlank() } ?: return@forEach
                result.putIfAbsent(userId, displayName)
            }
            result
        } catch (_: Exception) {
            emptyMap()
        }
    }

    internal fun resolveNicknamesBatch(
        userIds: Collection<Long>,
        linkId: Long? = null,
        chatId: Long? = null,
    ): Map<Long, String> {
        val orderedIds = userIds.distinct().filter { it > 0L }
        if (orderedIds.isEmpty()) return emptyMap()

        val resolved = LinkedHashMap<Long, String>(orderedIds.size)
        var unresolved = orderedIds.toSet()

        if (linkId != null && unresolved.isNotEmpty()) {
            val bindArgs =
                buildList<String?> {
                    add(linkId.toString())
                    addAll(unresolved.map { it.toString() })
                }.toTypedArray()
            val placeholders = unresolved.joinToString(",") { "?" }

            executeQuery(
                """
                SELECT user_id, nickname, enc
                FROM db2.open_chat_member
                WHERE link_id = ? AND user_id IN ($placeholders)
                """.trimIndent(),
                bindArgs,
                unresolved.size,
            ).forEach { row ->
                val userId = row["user_id"]?.toLongOrNull() ?: return@forEach
                val rawNick = row["nickname"] ?: return@forEach
                val enc = row["enc"]?.toIntOrNull() ?: 0
                resolved[userId] = if (enc > 0) decrypt(enc, rawNick, botId) else rawNick
            }
            unresolved = unresolved - resolved.keys
        }

        if (unresolved.isNotEmpty()) {
            val placeholders = unresolved.joinToString(",") { "?" }
            executeQuery(
                "SELECT id, name, enc FROM db2.friends WHERE id IN ($placeholders)",
                unresolved.map { it.toString() }.toTypedArray(),
                unresolved.size,
            ).forEach { row ->
                val userId = row["id"]?.toLongOrNull() ?: return@forEach
                val rawName = row["name"] ?: return@forEach
                val enc = row["enc"]?.toIntOrNull() ?: 0
                resolved[userId] = if (enc > 0) decrypt(enc, rawName, botId) else rawName
            }
            unresolved = unresolved - resolved.keys
        }

        if (unresolved.isNotEmpty()) {
            resolved.putAll(resolveObservedDisplayNamesBatch(unresolved, chatId))
        }

        return orderedIds.associateWith { userId -> resolved[userId] ?: userId.toString() }
    }

    private fun loadMemberActivityByUser(
        chatId: Long,
        memberIds: List<Long>,
    ): Map<Long, Map<String, String?>> {
        val memberIdSet = memberIds.toSet()
        if (memberIds.isEmpty()) {
            return emptyMap()
        }
        val nowMs = System.currentTimeMillis()
        val cached =
            synchronized(memberActivityCache) {
                memberActivityCache[chatId]?.takeIf {
                    it.memberIds == memberIdSet &&
                        nowMs - it.cachedAtMs <= MEMBER_ACTIVITY_CACHE_TTL_MS
                }
            }
        return cached?.activityByUser ?: run {
            val placeholders = memberIds.joinToString(", ") { "?" }
            val bindArgs = arrayOf<String?>(chatId.toString(), *memberIds.map(Long::toString).toTypedArray())
            val activity =
                executeQuery(
                    """
                    SELECT user_id, COUNT(*) as message_count, MAX(created_at) as last_active
                    FROM chat_logs
                    WHERE chat_id = ? AND user_id IN ($placeholders)
                    GROUP BY user_id
                    ORDER BY last_active DESC
                    """.trimIndent(),
                    bindArgs,
                    memberIds.size,
                ).associateBy { it["user_id"]?.toLongOrNull() ?: 0L }
            synchronized(memberActivityCache) {
                memberActivityCache[chatId] =
                    MemberActivityCacheEntry(
                        memberIds = memberIdSet,
                        cachedAtMs = nowMs,
                        activityByUser = activity,
                    )
            }
            activity
        }
    }

    private fun loadOpenNicknamesByUserIds(
        linkId: Long,
        userIds: List<Long>,
    ): Map<Long, String?> {
        val sortedUserIds = userIds.sorted()
        if (sortedUserIds.isEmpty()) {
            return emptyMap()
        }
        val placeholders = sortedUserIds.joinToString(", ") { "?" }
        val bindArgs = arrayOf<String?>(linkId.toString(), *sortedUserIds.map(Long::toString).toTypedArray())
        return executeQuery(
            """
            SELECT user_id, nickname, enc
            FROM db2.open_chat_member
            WHERE link_id = ? AND user_id IN ($placeholders)
            """.trimIndent(),
            bindArgs,
            sortedUserIds.size,
        ).associate { row ->
            val userId = row["user_id"]?.toLongOrNull() ?: 0L
            val enc = row["enc"]?.toIntOrNull() ?: 0
            val rawNick = row["nickname"]
            userId to if (rawNick != null && enc > 0) decrypt(enc, rawNick, botId) else rawNick
        }
    }

    private fun resolveNonOpenRoomName(
        chatId: Long,
        roomType: String?,
        meta: String?,
        members: String?,
    ): String? {
        val titleFromMeta = parseRoomTitle(meta)
        if (!titleFromMeta.isNullOrBlank()) {
            return titleFromMeta
        }

        val observedRoomName = resolveObservedProfileByChatId(chatId)?.roomName
        if (!observedRoomName.isNullOrBlank()) {
            return observedRoomName
        }

        val memberIds =
            parseJsonLongArray(members)
                .filter { it != botId }
                .toList()
        if (memberIds.isEmpty()) {
            return roomType
        }
        val names =
            resolveNicknamesBatch(memberIds.toList(), chatId = chatId)
                .values
                .filter { it.isNotBlank() }
        if (names.isEmpty()) {
            return roomType
        }
        return if (roomType == "DirectChat") names.first() else names.joinToString(", ")
    }

    private fun learnObservedProfileMappings(
        chatId: Long,
        members: List<MemberInfo>,
    ) {
        val visibleNames =
            members
                .asSequence()
                .filter { it.userId != botId }
                .mapNotNull { member ->
                    val nickname = member.nickname?.trim().orEmpty()
                    if (nickname.isBlank() || nickname == member.userId.toString()) {
                        null
                    } else {
                        member.userId to nickname
                    }
                }.toMap()
        if (visibleNames.isNotEmpty()) {
            learnObservedProfileUserMappings(chatId, visibleNames)
        }
    }

    private fun resolveObservedProfileByChatId(chatId: Long): ObservedProfileHint? =
        try {
            executeQuery(
                """
                SELECT display_name, room_name
                FROM db3.observed_profiles
                WHERE chat_id = ?
                ORDER BY updated_at DESC
                LIMIT 1
                """.trimIndent(),
                arrayOf(chatId.toString()),
                1,
            ).firstOrNull()?.let { row ->
                ObservedProfileHint(
                    displayName = row["display_name"]?.takeIf { it.isNotBlank() },
                    roomName = row["room_name"]?.takeIf { it.isNotBlank() },
                )
            }
        } catch (_: Exception) {
            null
        }

    private fun parseRoomTitle(meta: String?): String? {
        if (meta.isNullOrBlank()) return null
        return try {
            val element = json.parseToJsonElement(meta)
            val candidates =
                when {
                    element is kotlinx.serialization.json.JsonArray -> element
                    element is kotlinx.serialization.json.JsonObject ->
                        element["noticeActivityContents"]?.jsonArray ?: emptyList()
                    else -> emptyList()
                }
            candidates.firstNotNullOfOrNull { candidate ->
                val obj = candidate.jsonObject
                val type = obj["type"]?.jsonPrimitive?.content?.toIntOrNull()
                val content = obj["content"]?.jsonPrimitive?.content?.trim()
                content?.takeIf { type == 3 && it.isNotBlank() }
            }
        } catch (_: Exception) {
            null
        }
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

        resolveNicknamesBatch(memberIds, linkId = linkId, chatId = chatId).forEach { (userId, nickname) ->
            if (nickname.isNotBlank() && nickname != userId.toString()) {
                nicknames[userId] = nickname
            }
        }

        return RoomSnapshotData(
            chatId = chatId,
            linkId = linkId,
            memberIds = memberIds,
            blindedIds = blindedIds,
            nicknames = nicknames,
            roles = roles,
            profileImages = profileImages,
        ).also { snapshot ->
            if (snapshot.nicknames.isNotEmpty()) {
                learnObservedProfileUserMappings(chatId, snapshot.nicknames.filterValues { it.isNotBlank() })
            }
        }
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

    private fun resolveLinkId(chatId: Long): Long? =
        executeQuery(
            "SELECT link_id FROM chat_rooms WHERE id = ?",
            arrayOf(chatId.toString()),
            1,
        ).firstOrNull()?.get("link_id")?.toLongOrNull()

    private fun parseNotices(meta: String?): List<NoticeInfo> {
        if (meta.isNullOrBlank()) return emptyList()
        return try {
            val obj = json.parseToJsonElement(meta).jsonObject
            val noticesArray = obj["noticeActivityContents"]?.jsonArray ?: return emptyList()
            noticesArray.mapNotNull { elem ->
                try {
                    val notice = elem.jsonObject
                    NoticeInfo(
                        content = notice["message"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                        authorId = notice["authorId"]?.jsonPrimitive?.long ?: 0L,
                        updatedAt = notice["createdAt"]?.jsonPrimitive?.long ?: 0L,
                    )
                } catch (_: Exception) {
                    null
                }
            }
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
