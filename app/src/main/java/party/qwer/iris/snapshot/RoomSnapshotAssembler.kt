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
        val nicknames = mutableMapOf<UserId, String>()
        val roles = mutableMapOf<UserId, Int>()
        val profileImages = mutableMapOf<UserId, String>()

        for (member in openMembers) {
            val uid = member.userId
            val rawNick = member.nickname
            nicknames[uid] =
                if (rawNick != null && member.enc > 0) {
                    decrypt(member.enc, rawNick, botId.value)
                } else {
                    rawNick ?: ""
                }
            roles[uid] = member.linkMemberType
            member.profileImageUrl?.let { profileImages[uid] = it }
        }

        batchNicknames.forEach { (userId, nickname) ->
            if (nickname.isNotBlank() && nickname != userId.value.toString()) {
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
        )
    }
}
