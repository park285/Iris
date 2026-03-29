package party.qwer.iris

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.json.JSONObject
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
import party.qwer.iris.model.ThreadListResponse
import party.qwer.iris.model.ThreadSummary
import party.qwer.iris.model.roleCodeToName
import party.qwer.iris.snapshot.RoomSnapshotAssembler
import party.qwer.iris.storage.ChatId
import party.qwer.iris.storage.LinkId
import party.qwer.iris.storage.MemberActivityRow
import party.qwer.iris.storage.MemberIdentityQueries
import party.qwer.iris.storage.ObservedProfileQueries
import party.qwer.iris.storage.RoomDirectoryQueries
import party.qwer.iris.storage.RoomStatsQueries
import party.qwer.iris.storage.ThreadQueries
import party.qwer.iris.storage.UserId

class MemberRepository(
    private val roomDirectory: RoomDirectoryQueries,
    private val memberIdentity: MemberIdentityQueries,
    private val observedProfile: ObservedProfileQueries,
    private val roomStats: RoomStatsQueries,
    private val threadQueries: ThreadQueries,
    private val snapshotAssembler: RoomSnapshotAssembler = RoomSnapshotAssembler,
    private val decrypt: (Int, String, Long) -> String,
    private val botId: Long,
    private val learnObservedProfileUserMappings: (Long, Map<Long, String>) -> Unit = { _, _ -> },
) {
    private data class MemberActivityCacheEntry(
        val memberIds: Set<Long>,
        val cachedAtMs: Long,
        val activityByUser: Map<Long, MemberActivityRow>,
    )

    companion object {
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
        val roomRows = roomDirectory.listAllRooms()
        val rooms =
            roomRows.map { row ->
                RoomSummary(
                    chatId = row.id,
                    type = row.type,
                    linkId = row.linkId,
                    activeMembersCount = row.activeMembersCount,
                    linkName =
                        row.linkName
                            ?: resolveNonOpenRoomName(
                                chatId = row.id,
                                roomType = row.type,
                                meta = row.meta,
                                members = row.members,
                            ),
                    linkUrl = row.linkUrl,
                    memberLimit = row.memberLimit,
                    searchable = row.searchable,
                    botRole = row.botRole,
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
        val roomRow = roomDirectory.findRoomForListMembers(ChatId(chatId))
        val linkId = roomRow?.linkId
        val roomType = roomRow?.type.orEmpty()
        if (roomRow == null) {
            return MemberListResponse(chatId, null, emptyList(), 0)
        }

        if (linkId != null) {
            val openMembers = memberIdentity.loadOpenMembers(LinkId(linkId))
            val memberIds = openMembers.map { it.userId }.distinct()
            val activityByUser = loadMemberActivityByUser(chatId, memberIds)
            val members =
                openMembers.map { row ->
                    val activity = activityByUser[row.userId]
                    MemberInfo(
                        userId = row.userId,
                        nickname = row.nickname?.let { memberIdentity.decryptNickname(row.enc, it) },
                        role = roleCodeToName(row.linkMemberType),
                        roleCode = row.linkMemberType,
                        profileImageUrl = row.profileImageUrl,
                        messageCount = activity?.messageCount ?: 0,
                        lastActiveAt = activity?.lastActive,
                    )
                }
            learnObservedProfileMappings(chatId, members)
            return MemberListResponse(chatId, linkId, members, members.size)
        }

        val roomMemberIds = parseJsonLongArray(roomRow.members)
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
                roomStats.loadAllActivity(ChatId(chatId)).associateBy { it.userId }
            }
        val userIds =
            ((if (scopedMemberIds.isNotEmpty()) scopedMemberIds else activityByUser.keys.toList()) + activityByUser.keys)
                .sortedByDescending { activityByUser[it]?.lastActive ?: Long.MIN_VALUE }
                .distinct()
        val directChatParticipantId =
            if (roomType == "DirectChat") {
                userIds.filter { it != botId }.distinct().singleOrNull()
            } else {
                null
            }
        val observedProfileHint = observedProfile.resolveProfileByChatId(ChatId(chatId))
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
                        observedProfileHint?.displayName ?: resolvedNickname
                    } else {
                        resolvedNickname
                    }
                MemberInfo(
                    userId = userId,
                    nickname = nickname,
                    role = if (userId == botId) "bot" else roleCodeToName(roleCode),
                    roleCode = roleCode,
                    profileImageUrl = null,
                    messageCount = activity?.messageCount ?: 0,
                    lastActiveAt = activity?.lastActive,
                )
            }
        learnObservedProfileMappings(chatId, members)
        val totalCount = roomRow.activeMembersCount ?: members.size
        return MemberListResponse(chatId, null, members, maxOf(totalCount, members.size))
    }

    fun roomInfo(chatId: Long): RoomInfoResponse {
        val roomRow =
            roomDirectory.findRoomForInfo(ChatId(chatId))
                ?: return RoomInfoResponse(chatId, null, null, emptyList(), emptyList(), emptyList())

        val linkId = roomRow.linkId
        val notices = parseNotices(roomRow.meta)
        val blindedIds = parseJsonLongArray(roomRow.blindedMemberIds).toList()

        val botCommands =
            if (linkId != null) {
                roomDirectory.loadBotCommands(LinkId(linkId)).map { BotCommandInfo(it.first, it.second) }
            } else {
                emptyList()
            }

        val openLink =
            if (linkId != null) {
                roomDirectory.loadOpenLink(LinkId(linkId))?.let { row ->
                    OpenLinkInfo(
                        name = row.name,
                        url = row.url,
                        memberLimit = row.memberLimit,
                        description = row.description,
                        searchable = row.searchable,
                    )
                }
            } else {
                null
            }

        return RoomInfoResponse(
            chatId = chatId,
            type = roomRow.type,
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

        val rows = roomStats.loadTypeCountStats(ChatId(chatId), from)
        val byUser = rows.groupBy { it.userId }
        val nicknameByUser =
            if (linkId != null) {
                memberIdentity
                    .loadOpenNicknamesBatch(LinkId(linkId), byUser.keys.map { UserId(it) })
                    .mapKeys { it.key.value }
                    .mapValues { it.value ?: it.key.toString() }
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
                        val typeName = MESSAGE_TYPE_NAMES[row.type] ?: "other"
                        types[typeName] = (types[typeName] ?: 0) + row.count
                        total += row.count
                        val lastSeen = row.lastActive
                        if (lastSeen != null && (lastActive == null || lastSeen > lastActive)) {
                            lastActive = lastSeen
                        }
                    }
                    MemberStats(
                        userId = userId,
                        nickname = nicknameByUser[userId] ?: resolveNickname(userId, linkId = linkId),
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
        val periodSecs = parsePeriodSeconds(period)
        val now = System.currentTimeMillis() / 1000
        val from = if (periodSecs != null) now - periodSecs else 0L
        val linkId = resolveLinkId(chatId)

        val rows = roomStats.loadMessageLog(ChatId(chatId), UserId(userId), from)
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
            val typeName = MESSAGE_TYPE_NAMES[row.type] ?: "other"
            types[typeName] = (types[typeName] ?: 0) + 1
        }

        return MemberActivityResponse(
            userId = userId,
            nickname = resolveNickname(userId, linkId = linkId),
            messageCount = rows.size,
            firstMessageAt = firstAt,
            lastMessageAt = lastAt,
            activeHours = hours.toList(),
            messageTypes = types,
        )
    }

    fun listThreads(chatId: Long): ThreadListResponse {
        // 오픈채팅방(type이 'O'로 시작)에서만 thread가 의미 있음
        val room = roomDirectory.findRoomById(ChatId(chatId))
        val isOpenChat = room?.type?.startsWith("O") == true
        if (!isOpenChat) {
            return ThreadListResponse(chatId = chatId, threads = emptyList())
        }

        val rows = threadQueries.listThreads(ChatId(chatId))
        val threads =
            rows.map { row ->
                val decryptedOrigin =
                    if (row.originMessage != null && row.originV != null) {
                        try {
                            val enc = JSONObject(row.originV).optInt("enc", 0)
                            val userId = row.originUserId ?: botId
                            decrypt(enc, row.originMessage, userId)
                        } catch (_: Exception) {
                            row.originMessage
                        }
                    } else {
                        null
                    }
                ThreadSummary(
                    threadId = row.threadId.toString(),
                    originMessage = decryptedOrigin,
                    messageCount = row.messageCount,
                    lastActiveAt = row.lastActiveAt,
                )
            }
        return ThreadListResponse(chatId = chatId, threads = threads)
    }

    private fun resolveNickname(
        userId: Long,
        linkId: Long? = null,
        chatId: Long? = null,
    ): String? {
        if (linkId != null) {
            val openNickname = memberIdentity.resolveOpenNickname(UserId(userId), LinkId(linkId))
            if (openNickname != null) return openNickname
        }

        val friendName = memberIdentity.resolveFriendName(UserId(userId))
        if (friendName != null) return friendName

        return observedProfile.resolveDisplayNamesBatch(listOf(userId), chatId)[userId] ?: userId.toString()
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
            val openNicknames =
                memberIdentity
                    .loadOpenNicknamesBatch(LinkId(linkId), unresolved.map(::UserId))
                    .mapKeys { it.key.value }

            openNicknames.forEach { (userId, nickname) ->
                if (!nickname.isNullOrBlank()) {
                    resolved[userId] = nickname
                }
            }
            unresolved = unresolved - resolved.keys
        }

        if (unresolved.isNotEmpty()) {
            val friendNames =
                memberIdentity
                    .loadFriendsBatch(unresolved.map(::UserId))
                    .mapKeys { it.key.value }

            resolved.putAll(friendNames)
            unresolved = unresolved - friendNames.keys
        }

        if (unresolved.isNotEmpty()) {
            resolved.putAll(observedProfile.resolveDisplayNamesBatch(unresolved.toList(), chatId))
        }

        return orderedIds.associateWith { userId -> resolved[userId] ?: userId.toString() }
    }

    private fun loadMemberActivityByUser(
        chatId: Long,
        memberIds: List<Long>,
    ): Map<Long, MemberActivityRow> {
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
        return cached?.activityByUser
            ?: run {
                val activity = roomStats.loadMemberActivity(ChatId(chatId), memberIds).associateBy { it.userId }
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

        val observedRoomName = observedProfile.resolveProfileByChatId(ChatId(chatId))?.roomName
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
            resolveNicknamesBatch(memberIds, chatId = chatId)
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

    fun snapshot(chatId: Long): RoomSnapshotData {
        val roomRow = roomDirectory.findRoomForSnapshot(ChatId(chatId))
        val linkId = roomRow?.linkId
        val memberIds = parseJsonLongArray(roomRow?.members)
        val blindedIds = parseJsonLongArray(roomRow?.blindedMemberIds)
        val openMembers =
            if (linkId != null) {
                memberIdentity.loadOpenMembers(LinkId(linkId))
            } else {
                emptyList()
            }
        val batchNicknames = resolveNicknamesBatch(memberIds, linkId = linkId, chatId = chatId)

        return snapshotAssembler
            .assemble(
                chatId = chatId,
                linkId = linkId,
                memberIds = memberIds,
                blindedIds = blindedIds,
                openMembers = openMembers,
                batchNicknames = batchNicknames,
                decrypt = decrypt,
                botId = botId,
            ).also { snapshot ->
                if (snapshot.nicknames.isNotEmpty()) {
                    learnObservedProfileUserMappings(
                        chatId,
                        snapshot.nicknames.filterValues { it.isNotBlank() },
                    )
                }
            }
    }

    fun parseJsonLongArray(raw: String?): Set<Long> {
        if (raw.isNullOrBlank() || raw == "[]") return emptySet()
        return try {
            json
                .parseToJsonElement(raw)
                .jsonArray
                .mapNotNull { it.jsonPrimitive.long }
                .toSet()
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
        return memberIdentity.resolveSenderRole(UserId(userId), LinkId(linkId))
    }

    private fun resolveLinkId(
        chatId: Long,
    ): Long? = roomDirectory.resolveLinkId(ChatId(chatId))?.value

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
