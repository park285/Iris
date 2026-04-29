package party.qwer.iris

import party.qwer.iris.snapshot.RoomSnapshotAssembler
import party.qwer.iris.snapshot.RoomSnapshotReadResult
import party.qwer.iris.storage.ChatId
import party.qwer.iris.storage.LinkId
import party.qwer.iris.storage.MemberIdentityQueries
import party.qwer.iris.storage.RoomDirectoryQueries
import party.qwer.iris.storage.UserId

internal class RoomSnapshotService(
    private val roomDirectory: RoomDirectoryQueries,
    private val memberIdentity: MemberIdentityQueries,
    private val snapshotAssembler: RoomSnapshotAssembler,
    private val parseJsonLongArray: (String?) -> Set<Long>,
    private val parseJsonLongArrays: (List<String?>) -> List<Set<Long>> = { rawValues ->
        rawValues.map(parseJsonLongArray)
    },
    private val resolveNicknamesBatch: (
        userIds: Collection<UserId>,
        linkId: LinkId?,
        chatId: ChatId?,
    ) -> Map<UserId, String>,
    private val excludeFriendResolvedUsers: (Map<Long, String>) -> Map<Long, String>,
    private val learnObservedProfileUserMappings: (Long, Map<Long, String>) -> Unit,
    private val decrypt: (Int, String, Long) -> String,
    private val botId: Long,
) {
    fun snapshot(chatId: Long): RoomSnapshotReadResult {
        val roomId = ChatId(chatId)
        val roomRow = roomDirectory.findRoomForSnapshot(roomId) ?: return RoomSnapshotReadResult.Missing
        val linkId = roomRow.linkId
        val parsedIdArrays = parseJsonLongArrays(listOf(roomRow.members, roomRow.blindedMemberIds))
        val memberIds = parsedIdArrays.getOrElse(0) { parseJsonLongArray(roomRow.members) }.map(::UserId)
        val blindedIds = parsedIdArrays.getOrElse(1) { parseJsonLongArray(roomRow.blindedMemberIds) }.map(::UserId)
        val openMembers =
            if (linkId != null) {
                memberIdentity.loadOpenMembers(linkId)
            } else {
                emptyList()
            }
        val batchNicknames =
            if (linkId != null) {
                memberIdentity
                    .loadOpenNicknamesBatch(linkId, memberIds.toList())
                    .mapNotNull { (userId, nickname) ->
                        nickname?.trim()?.takeIf { it.isNotBlank() }?.let { userId to it }
                    }.toMap(linkedMapOf())
            } else {
                resolveNicknamesBatch(memberIds, linkId, roomId)
            }

        val snapshot =
            snapshotAssembler
                .assemble(
                    chatId = roomId,
                    linkId = linkId,
                    memberIds = memberIds.toCollection(linkedSetOf()),
                    blindedIds = blindedIds.toCollection(linkedSetOf()),
                    openMembers = openMembers,
                    batchNicknames = batchNicknames,
                    decrypt = decrypt,
                    botId = UserId(botId),
                ).also { assembled ->
                    val longNicknames =
                        assembled.nicknames
                            .filterValues { it.isNotBlank() }
                            .mapKeys { (userId, _) -> userId.value }
                    val learnable = excludeFriendResolvedUsers(longNicknames)
                    if (learnable.isNotEmpty()) {
                        learnObservedProfileUserMappings(chatId, learnable)
                    }
                }
        return RoomSnapshotReadResult.Present(snapshot)
    }
}
