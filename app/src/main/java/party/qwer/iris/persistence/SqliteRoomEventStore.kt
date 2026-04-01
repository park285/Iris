package party.qwer.iris.persistence

import party.qwer.iris.model.RoomEventRecord

class SqliteRoomEventStore(
    private val driver: SqliteDriver,
) : RoomEventStore {
    override fun insert(chatId: Long, eventType: String, userId: Long, payload: String, createdAtMs: Long): Long {
        driver.update(
            "INSERT INTO ${IrisDatabaseSchema.ROOM_EVENTS_TABLE} (chat_id, event_type, user_id, payload, created_at) VALUES (?, ?, ?, ?, ?)",
            listOf(chatId, eventType, userId, payload, createdAtMs),
        )
        return driver.queryLong("SELECT last_insert_rowid()") ?: 0L
    }

    override fun listByChatId(chatId: Long, limit: Int, afterId: Long): List<RoomEventRecord> =
        driver.query(
            "SELECT id, chat_id, event_type, user_id, payload, created_at FROM ${IrisDatabaseSchema.ROOM_EVENTS_TABLE} WHERE chat_id = ? AND id > ? ORDER BY id ASC LIMIT ?",
            listOf(chatId, afterId, limit),
        ) { row ->
            RoomEventRecord(
                id = row.getLong(0),
                chatId = row.getLong(1),
                eventType = row.getString(2),
                userId = row.getLong(3),
                payload = row.getString(4),
                createdAt = row.getLong(5),
            )
        }

    override fun maxId(): Long =
        driver.queryLong("SELECT MAX(id) FROM ${IrisDatabaseSchema.ROOM_EVENTS_TABLE}") ?: 0L

    override fun pruneOlderThan(cutoffMs: Long): Int =
        driver.update(
            "DELETE FROM ${IrisDatabaseSchema.ROOM_EVENTS_TABLE} WHERE created_at < ?",
            listOf(cutoffMs),
        )
}
