package party.qwer.iris.snapshot

import party.qwer.iris.RoomSnapshotData

interface RoomSnapshotReader {
    fun listRoomChatIds(): List<Long>

    fun snapshot(chatId: Long): RoomSnapshotData
}
