package party.qwer.iris.persistence

import party.qwer.iris.http.SseEventEnvelope

class SqliteSseEventStore(
    private val driver: SqliteDriver,
) : SseEventStore {
    override fun insert(
        eventType: String,
        payload: String,
        createdAtMs: Long,
    ): Long {
        driver.update(
            "INSERT INTO ${IrisDatabaseSchema.SSE_EVENTS_TABLE} (event_type, payload, created_at) VALUES (?, ?, ?)",
            listOf(eventType, payload, createdAtMs),
        )
        return driver.queryLong("SELECT last_insert_rowid()") ?: 0L
    }

    override fun replayAfter(
        afterId: Long,
        limit: Int,
    ): List<SseEventEnvelope> =
        driver.query(
            "SELECT id, event_type, payload, created_at FROM ${IrisDatabaseSchema.SSE_EVENTS_TABLE} WHERE id > ? ORDER BY id ASC LIMIT ?",
            listOf(afterId, limit),
        ) { row ->
            SseEventEnvelope(
                id = row.getLong(0),
                eventType = row.getString(1),
                payload = row.getString(2),
                createdAtMs = row.getLong(3),
            )
        }

    override fun maxId(): Long = driver.queryLong("SELECT MAX(id) FROM ${IrisDatabaseSchema.SSE_EVENTS_TABLE}") ?: 0L

    override fun prune(keepCount: Int) {
        require(keepCount > 0) { "keepCount must be positive" }
        driver.execute(
            "DELETE FROM ${IrisDatabaseSchema.SSE_EVENTS_TABLE} WHERE id NOT IN (SELECT id FROM ${IrisDatabaseSchema.SSE_EVENTS_TABLE} ORDER BY id DESC LIMIT $keepCount)",
        )
    }
}
