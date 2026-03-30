package party.qwer.iris.snapshot

import party.qwer.iris.RoomSnapshotData
import party.qwer.iris.storage.ChatId
import party.qwer.iris.storage.LinkId
import party.qwer.iris.storage.OpenMemberRow
import party.qwer.iris.storage.UserId

object RoomSnapshotAssembler {
    fun assemble(
        chatId: ChatId,
        linkId: LinkId?,
        memberIds: Set<UserId>,
        blindedIds: Set<UserId>,
        openMembers: List<OpenMemberRow>,
        batchNicknames: Map<UserId, String>,
        decrypt: (Int, String, Long) -> String,
        botId: UserId,
    ): RoomSnapshotData {
        val nicknames = mutableMapOf<Long, String>()
        val roles = mutableMapOf<Long, Int>()
        val profileImages = mutableMapOf<Long, String>()

        for (member in openMembers) {
            val rawNick = member.nickname
            nicknames[member.userId] =
                if (rawNick != null && member.enc > 0) {
                    decrypt(member.enc, rawNick, botId.value)
                } else {
                    rawNick ?: ""
                }
            roles[member.userId] = member.linkMemberType
            member.profileImageUrl?.let { profileImages[member.userId] = it }
        }

        batchNicknames.forEach { (userId, nickname) ->
            if (nickname.isNotBlank() && nickname != userId.value.toString()) {
                nicknames[userId.value] = nickname
            }
        }

        return RoomSnapshotData(
            chatId = chatId.value,
            linkId = linkId?.value,
            memberIds = memberIds.mapTo(linkedSetOf()) { it.value },
            blindedIds = blindedIds.mapTo(linkedSetOf()) { it.value },
            nicknames = nicknames,
            roles = roles,
            profileImages = profileImages,
        )
    }
}
