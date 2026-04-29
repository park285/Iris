package party.qwer.iris

import party.qwer.iris.model.MemberInfo
import party.qwer.iris.model.MemberListResponse
import party.qwer.iris.model.roleCodeToName
import party.qwer.iris.storage.ChatId
import party.qwer.iris.storage.MemberActivityRow
import party.qwer.iris.storage.MemberIdentityQueries
import party.qwer.iris.storage.ObservedProfileQueries
import party.qwer.iris.storage.RoomDirectoryQueries
import party.qwer.iris.storage.RoomStatsQueries
import party.qwer.iris.storage.UserId

internal class MemberListingService(
    private val roomDirectory: RoomDirectoryQueries,
    private val memberIdentity: MemberIdentityQueries,
    private val observedProfile: ObservedProfileQueries,
    private val roomStats: RoomStatsQueries,
    private val memberActivityLookup: MemberActivityLookup,
    private val parseJsonLongArray: (String?) -> Set<Long>,
    private val prepareNicknameLookup: (
        userIds: Collection<UserId>,
        linkId: party.qwer.iris.storage.LinkId?,
        chatId: ChatId?,
    ) -> Map<UserId, String>,
    private val learnObservedProfileMappings: (Long, List<MemberInfo>) -> Unit,
    private val botId: Long,
) {
    fun listMembers(chatId: ChatId): MemberListResponse {
        val roomRow = roomDirectory.findRoomForListMembers(chatId)
        val linkId = roomRow?.linkId
        val roomType = roomRow?.type.orEmpty()
        if (roomRow == null) {
            return MemberListResponse(chatId.value, null, emptyList(), 0)
        }

        if (linkId != null) {
            val openMembers = memberIdentity.loadOpenMembers(linkId)
            val memberIds = openMembers.map { it.userId }.distinct()
            val activityByUser = memberActivityLookup.loadByUser(chatId, memberIds)
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
            learnObservedProfileMappings(chatId.value, members)
            return MemberListResponse(chatId.value, linkId.value, members, members.size)
        }

        val roomMemberIds = parseJsonLongArray(roomRow.members).map(::UserId)
        val scopedMemberIds = scopedNonOpenMemberIds(roomMemberIds, botId)
        val activityByUser =
            if (scopedMemberIds.isNotEmpty()) {
                memberActivityLookup.loadByUser(chatId, scopedMemberIds)
            } else {
                roomStats.loadAllActivity(chatId).associateBy { it.userId }
            }
        val userIds = orderNonOpenMemberIds(scopedMemberIds, activityByUser)
        val directChatParticipantId = directChatParticipantId(roomType, userIds, botId)
        val observedProfileHint = observedProfile.resolveProfileByChatId(chatId)
        val nicknameByUser = prepareNicknameLookup(userIds, null, chatId)
        val members =
            userIds.map { userId ->
                val roleCode = if (userId.value == botId) 8 else 2
                val activity = activityByUser[userId]
                val resolvedNickname = nicknameByUser[userId] ?: userId.value.toString()
                val nickname =
                    if (
                        KakaoRoomType.isDirectChat(roomType) &&
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
        learnObservedProfileMappings(chatId.value, members)
        val totalCount = roomRow.activeMembersCount ?: members.size
        return MemberListResponse(chatId.value, null, members, maxOf(totalCount, members.size))
    }
}

private fun scopedNonOpenMemberIds(
    roomMemberIds: List<UserId>,
    botId: Long,
): List<UserId> =
    buildSet {
        addAll(roomMemberIds)
        if (botId > 0L) {
            add(UserId(botId))
        }
    }.toList()

private fun orderNonOpenMemberIds(
    scopedMemberIds: List<UserId>,
    activityByUser: Map<UserId, MemberActivityRow>,
): List<UserId> {
    val baseIds = if (scopedMemberIds.isNotEmpty()) scopedMemberIds else activityByUser.keys.toList()
    return (baseIds + activityByUser.keys)
        .sortedByDescending { activityByUser[it]?.lastActive ?: Long.MIN_VALUE }
        .distinct()
}

private fun directChatParticipantId(
    roomType: String,
    userIds: List<UserId>,
    botId: Long,
): UserId? =
    if (KakaoRoomType.isDirectChat(roomType)) {
        userIds.filter { it.value != botId }.distinct().singleOrNull()
    } else {
        null
    }
