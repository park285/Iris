package party.qwer.iris.snapshot

import party.qwer.iris.RoomSnapshotData
import party.qwer.iris.storage.OpenMemberRow

object RoomSnapshotAssembler {
    fun assemble(
        chatId: Long,
        linkId: Long?,
        memberIds: Set<Long>,
        blindedIds: Set<Long>,
        openMembers: List<OpenMemberRow>,
        batchNicknames: Map<Long, String>,
        decrypt: (Int, String, Long) -> String,
        botId: Long,
    ): RoomSnapshotData {
        val nicknames = mutableMapOf<Long, String>()
        val roles = mutableMapOf<Long, Int>()
        val profileImages = mutableMapOf<Long, String>()

        for (member in openMembers) {
            val rawNick = member.nickname
            nicknames[member.userId] =
                if (rawNick != null && member.enc > 0) {
                    decrypt(member.enc, rawNick, botId)
                } else {
                    rawNick ?: ""
                }
            roles[member.userId] = member.linkMemberType
            member.profileImageUrl?.let { profileImages[member.userId] = it }
        }

        batchNicknames.forEach { (userId, nickname) ->
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
        )
    }
}
