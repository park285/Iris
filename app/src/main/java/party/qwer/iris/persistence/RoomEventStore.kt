package party.qwer.iris.persistence

import party.qwer.iris.model.RoomEventRecord

interface RoomEventStore {
    fun insert(chatId: Long, eventType: String, userId: Long, payload: String, createdAtMs: Long): Long
    fun listByChatId(chatId: Long, limit: Int, afterId: Long = 0): List<RoomEventRecord>
    fun maxId(): Long
    fun pruneOlderThan(cutoffMs: Long): Int
}
