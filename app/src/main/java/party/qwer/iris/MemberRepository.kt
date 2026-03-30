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
        val memberIds: Set<UserId>,
        val cachedAtMs: Long,
        val activityByUser: Map<UserId, MemberActivityRow>,
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

    private val memberActivityCache = mutableMapOf<ChatId, MemberActivityCacheEntry>()

    fun listRooms(): RoomListResponse {
        val roomRows = roomDirectory.listAllRooms()
        val rooms =
            roomRows.map { row ->
                RoomSummary(
                    chatId = row.id.value,
                    type = row.type,
                    linkId = row.linkId?.value,
                    activeMembersCount = row.activeMembersCount,
                    linkName =
                        row.linkName
                            ?: resolveNonOpenRoomName(
                                chatId = row.id.value,
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
        val roomId = ChatId(chatId)
        val roomRow = roomDirectory.findRoomForListMembers(roomId)
        val linkId = roomRow?.linkId
        val roomType = roomRow?.type.orEmpty()
        if (roomRow == null) {
            return MemberListResponse(chatId, null, emptyList(), 0)
        }

        if (linkId != null) {
            val openMembers = memberIdentity.loadOpenMembers(linkId)
            val memberIds = openMembers.map { it.userId }.distinct()
            val activityByUser = loadMemberActivityByUser(roomId, memberIds)
            val members =
                openMembers.map { row ->
                    val activity = activityByUser[row.userId]
                    MemberInfo(
                        userId = row.userId.value,
                        nickname = row.nickname?.let { memberIdentity.decryptNickname(row.enc, it) },
                        role = roleCodeToName(row.linkMemberType),
                        roleCode = row.linkMemberType,
                        profileImageUrl = row.profileImageUrl,
                        messageCount = activity?.messageCount ?: 0,
                        lastActiveAt = activity?.lastActive,
                    )
                }
            learnObservedProfileMappings(chatId, members)
            return MemberListResponse(chatId, linkId.value, members, members.size)
        }

        val roomMemberIds = parseJsonLongArray(roomRow.members).map(::UserId)
        val scopedMemberIds =
            buildSet {
                addAll(roomMemberIds)
                if (botId > 0L) {
                    add(UserId(botId))
                }
            }.toList()
        val activityByUser =
            if (scopedMemberIds.isNotEmpty()) {
                loadMemberActivityByUser(roomId, scopedMemberIds)
            } else {
                roomStats.loadAllActivity(roomId).associateBy { it.userId }
            }
        val userIds =
            ((if (scopedMemberIds.isNotEmpty()) scopedMemberIds else activityByUser.keys.toList()) + activityByUser.keys)
                .sortedByDescending { activityByUser[it]?.lastActive ?: Long.MIN_VALUE }
                .distinct()
        val directChatParticipantId =
            if (roomType == "DirectChat") {
                userIds.filter { it.value != botId }.distinct().singleOrNull()
            } else {
                null
            }
        val observedProfileHint = observedProfile.resolveProfileByChatId(roomId)
        val nicknameByUser = prepareNicknameLookup(userIds, chatId = roomId)
        val members =
            userIds.map { userId ->
                val roleCode = if (userId.value == botId) 8 else 2
                val activity = activityByUser[userId]
                val resolvedNickname = nicknameByUser[userId] ?: userId.value.toString()
                val nickname =
                    if (
                        roomType == "DirectChat" &&
                        userId == directChatParticipantId &&
                        resolvedNickname == userId.value.toString()
                    ) {
                        observedProfileHint?.displayName ?: resolvedNickname
                    } else {
                        resolvedNickname
                    }
                MemberInfo(
                    userId = userId.value,
                    nickname = nickname,
                    role = if (userId.value == botId) "bot" else roleCodeToName(roleCode),
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
                roomDirectory.loadBotCommands(linkId).map { BotCommandInfo(it.first, it.second) }
            } else {
                emptyList()
            }

        val openLink =
            if (linkId != null) {
                roomDirectory.loadOpenLink(linkId)?.let { row ->
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
            linkId = linkId?.value,
            notices = notices,
            blindedMemberIds = blindedIds,
            botCommands = botCommands,
            openLink = openLink,
        )
    }

    fun resolveDisplayName(
        userId: Long,
        chatId: Long,
        linkId: Long? = resolveLinkId(ChatId(chatId))?.value,
    ): String =
        resolveNickname(
            userId = UserId(userId),
            linkId = linkId?.let(::LinkId),
            chatId = ChatId(chatId),
        ) ?: userId.toString()

    fun roomStats(
        chatId: Long,
        period: String?,
        limit: Int,
        minMessages: Int = 0,
    ): StatsResponse {
        val periodSecs = parsePeriodSeconds(period)
        val now = System.currentTimeMillis() / 1000
        val from = if (periodSecs != null) now - periodSecs else 0L
        val roomId = ChatId(chatId)
        val linkId = resolveLinkId(roomId)

        val rows = roomStats.loadTypeCountStats(roomId, from)
        val byUser = rows.groupBy { it.userId }
        val nicknameByUser = prepareNicknameLookup(byUser.keys, linkId = linkId, chatId = roomId)
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
        val periodSecs = parsePeriodSeconds(period)
        val now = System.currentTimeMillis() / 1000
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
            val typeName = MESSAGE_TYPE_NAMES[row.type] ?: "other"
            types[typeName] = (types[typeName] ?: 0) + 1
        }

        return MemberActivityResponse(
            userId = userId,
            nickname = resolveNickname(UserId(userId), linkId = linkId, chatId = roomId) ?: userId.toString(),
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
                            val userId = row.originUserId?.value ?: botId
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
        userId: UserId,
        linkId: LinkId? = null,
        chatId: ChatId? = null,
    ): String? {
        if (linkId != null) {
            val openNickname = memberIdentity.resolveOpenNickname(userId, linkId)
            if (openNickname != null) return openNickname
        }

        val friendName = memberIdentity.resolveFriendName(userId)
        if (friendName != null) return friendName

        return observedProfile.resolveDisplayNamesBatch(listOf(userId), chatId)[userId] ?: userId.value.toString()
    }

    internal fun resolveNicknamesBatch(
        userIds: Collection<UserId>,
        linkId: LinkId? = null,
        chatId: ChatId? = null,
    ): Map<UserId, String> {
        val orderedIds = userIds.distinct().filter { it.value > 0L }
        if (orderedIds.isEmpty()) return emptyMap()

        val resolved = LinkedHashMap<UserId, String>(orderedIds.size)
        var unresolved = orderedIds.toSet()

        if (linkId != null && unresolved.isNotEmpty()) {
            val openNicknames =
                memberIdentity
                    .loadOpenNicknamesBatch(linkId, unresolved.toList())

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
                    .loadFriendsBatch(unresolved.toList())

            resolved.putAll(friendNames)
            unresolved = unresolved - friendNames.keys
        }

        if (unresolved.isNotEmpty()) {
            observedProfile
                .resolveDisplayNamesBatch(unresolved.toList(), chatId)
                .forEach { (userId, displayName) ->
                    resolved[userId] = displayName
                }
        }

        return orderedIds.associateWith { userId -> resolved[userId] ?: userId.value.toString() }
    }

    private fun prepareNicknameLookup(
        userIds: Collection<UserId>,
        linkId: LinkId? = null,
        chatId: ChatId? = null,
    ): Map<UserId, String> {
        val orderedIds = userIds.distinct().filter { it.value > 0L }
        if (orderedIds.isEmpty()) return emptyMap()
        return if (linkId != null) {
            memberIdentity
                .loadOpenNicknamesBatch(linkId, orderedIds)
                .mapValues { (userId, nickname) -> nickname ?: userId.value.toString() }
        } else {
            resolveNicknamesBatch(orderedIds, chatId = chatId)
        }
    }

    private fun loadMemberActivityByUser(
        chatId: ChatId,
        memberIds: List<UserId>,
    ): Map<UserId, MemberActivityRow> {
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
                val activity = roomStats.loadMemberActivity(chatId, memberIds).associateBy { it.userId }
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
                .map(::UserId)
        if (memberIds.isEmpty()) {
            return roomType
        }
        val names =
            resolveNicknamesBatch(memberIds, chatId = ChatId(chatId))
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
        val nonBotMembers = members.filter { it.userId != botId }
        val visibleNames =
            nonBotMembers
                .asSequence()
                .mapNotNull { member ->
                    val nickname = member.nickname?.trim().orEmpty()
                    if (nickname.isBlank() || nickname == member.userId.toString()) {
                        null
                    } else {
                        member.userId to nickname
                    }
                }.toMap()
        val learnable = excludeFriendResolvedUsers(visibleNames)
        if (learnable.isNotEmpty()) {
            learnObservedProfileUserMappings(chatId, learnable)
        }
    }

    private fun excludeFriendResolvedUsers(userDisplayNames: Map<Long, String>): Map<Long, String> {
        if (userDisplayNames.isEmpty()) return userDisplayNames
        val friendIds =
            memberIdentity
                .loadFriendsBatch(userDisplayNames.keys.map(::UserId))
                .keys
                .mapTo(mutableSetOf()) { it.value }
        return if (friendIds.isEmpty()) userDisplayNames else userDisplayNames.filterKeys { it !in friendIds }
    }

    fun snapshot(chatId: Long): RoomSnapshotData {
        val roomId = ChatId(chatId)
        val roomRow = roomDirectory.findRoomForSnapshot(roomId)
        val linkId = roomRow?.linkId
        val memberIds = parseJsonLongArray(roomRow?.members).map(::UserId)
        val blindedIds = parseJsonLongArray(roomRow?.blindedMemberIds).map(::UserId)
        val openMembers =
            if (linkId != null) {
                memberIdentity.loadOpenMembers(linkId)
            } else {
                emptyList()
            }
        val batchNicknames = resolveNicknamesBatch(memberIds, linkId = linkId, chatId = roomId)

        return snapshotAssembler
            .assemble(
                chatId = roomId,
                linkId = linkId,
                memberIds = memberIds.toCollection(linkedSetOf()),
                blindedIds = blindedIds.toCollection(linkedSetOf()),
                openMembers = openMembers,
                batchNicknames = batchNicknames,
                decrypt = decrypt,
                botId = UserId(botId),
            ).also { snapshot ->
                val longNicknames =
                    snapshot.nicknames
                        .filterValues { it.isNotBlank() }
                        .mapKeys { (k, _) -> k.value }
                val learnable = excludeFriendResolvedUsers(longNicknames)
                if (learnable.isNotEmpty()) {
                    learnObservedProfileUserMappings(chatId, learnable)
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
        chatId: ChatId,
    ): LinkId? = roomDirectory.resolveLinkId(chatId)

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
    val chatId: ChatId,
    val linkId: LinkId?,
    val memberIds: Set<UserId>,
    val blindedIds: Set<UserId>,
    val nicknames: Map<UserId, String>,
    val roles: Map<UserId, Int>,
    val profileImages: Map<UserId, String>,
)
