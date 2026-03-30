package party.qwer.iris

import party.qwer.iris.model.MemberEvent
import party.qwer.iris.model.NicknameChangeEvent
import party.qwer.iris.model.ProfileChangeEvent
import party.qwer.iris.model.RoleChangeEvent
import party.qwer.iris.model.roleCodeToName
import party.qwer.iris.snapshot.RoomDiffEngine

open class RoomSnapshotManager : RoomDiffEngine {
    override fun diff(
        prev: RoomSnapshotData,
        curr: RoomSnapshotData,
    ): List<Any> {
        val events = mutableListOf<Any>()
        val now = System.currentTimeMillis() / 1000

        val joined = curr.memberIds - prev.memberIds
        for (uid in joined) {
            events.add(
                MemberEvent(
                    event = "join",
                    chatId = curr.chatId.value,
                    linkId = curr.linkId?.value,
                    userId = uid.value,
                    nickname = curr.nicknames[uid],
                    estimated = false,
                    timestamp = now,
                ),
            )
        }

        val left = prev.memberIds - curr.memberIds
        for (uid in left) {
            val isKicked = uid in curr.blindedIds && uid !in prev.blindedIds
            events.add(
                MemberEvent(
                    event = if (isKicked) "kick" else "leave",
                    chatId = curr.chatId.value,
                    linkId = curr.linkId?.value,
                    userId = uid.value,
                    nickname = prev.nicknames[uid],
                    estimated = !isKicked,
                    timestamp = now,
                ),
            )
        }

        // 닉네임 변경 (현재 재실 중인 멤버만 대상)
        val commonMembers = prev.memberIds.intersect(curr.memberIds)
        for (uid in commonMembers) {
            val oldNick = prev.nicknames[uid]
            val newNick = curr.nicknames[uid]
            if (oldNick != null && newNick != null && oldNick != newNick) {
                events.add(
                    NicknameChangeEvent(
                        chatId = curr.chatId.value,
                        linkId = curr.linkId?.value,
                        userId = uid.value,
                        oldNickname = oldNick,
                        newNickname = newNick,
                        timestamp = now,
                    ),
                )
            }

            val oldRole = prev.roles[uid]
            val newRole = curr.roles[uid]
            if (oldRole != null && newRole != null && oldRole != newRole) {
                events.add(
                    RoleChangeEvent(
                        chatId = curr.chatId.value,
                        linkId = curr.linkId?.value,
                        userId = uid.value,
                        oldRole = roleCodeToName(oldRole),
                        newRole = roleCodeToName(newRole),
                        timestamp = now,
                    ),
                )
            }

            val oldImg = prev.profileImages[uid]
            val newImg = curr.profileImages[uid]
            if (oldImg != newImg) {
                events.add(
                    ProfileChangeEvent(
                        chatId = curr.chatId.value,
                        linkId = curr.linkId?.value,
                        userId = uid.value,
                        timestamp = now,
                    ),
                )
            }
        }

        return events
    }
}
