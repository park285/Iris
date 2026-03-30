package party.qwer.iris.snapshot

import party.qwer.iris.storage.ChatId

interface RoomSnapshotReader {
    fun listRoomChatIds(): List<ChatId>

    fun snapshot(chatId: ChatId): RoomSnapshotReadResult
}
