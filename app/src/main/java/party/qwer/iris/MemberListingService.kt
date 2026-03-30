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
            val activityByUser = memberActivityLookup.loadByUser(roomId, memberIds)
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
                memberActivityLookup.loadByUser(roomId, scopedMemberIds)
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
        val nicknameByUser = prepareNicknameLookup(userIds, null, roomId)
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
}
