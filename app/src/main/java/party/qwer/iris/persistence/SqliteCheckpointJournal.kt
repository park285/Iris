package party.qwer.iris.persistence

class SqliteCheckpointJournal(
    private val db: SqliteDriver,
    private val clock: () -> Long = System::currentTimeMillis,
) : CheckpointJournal {
    private val pending = mutableMapOf<String, Long>()

    override fun advance(
        stream: String,
        cursor: Long,
    ) {
        pending[stream] = cursor
    }

    override fun flushIfDirty() {
        flushNow()
    }

    override fun flushNow() {
        if (pending.isEmpty()) {
            return
        }
        val snapshot = pending.toMap()
        db.inImmediateTransaction {
            snapshot.forEach { (stream, cursor) ->
                update(
                    """INSERT INTO ${IrisDatabaseSchema.CHECKPOINT_TABLE} (stream, cursor_value, updated_at)
                       VALUES (?, ?, ?)
                       ON CONFLICT(stream) DO UPDATE SET
                           cursor_value = excluded.cursor_value,
                           updated_at = excluded.updated_at""",
                    listOf(stream, cursor, clock()),
                )
            }
        }
        pending.keys.removeAll(snapshot.keys)
    }

    override fun load(stream: String): Long? =
        pending[stream]
            ?: db.queryLong(
                "SELECT cursor_value FROM ${IrisDatabaseSchema.CHECKPOINT_TABLE} WHERE stream = ?",
                stream,
            )
}
