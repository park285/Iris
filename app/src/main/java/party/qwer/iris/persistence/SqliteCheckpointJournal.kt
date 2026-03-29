package party.qwer.iris.persistence

class SqliteCheckpointJournal(
    private val db: SqliteDriver,
    private val clock: () -> Long = System::currentTimeMillis,
) : CheckpointJournal {
    private val pending = mutableMapOf<String, Long>()

    @Synchronized
    override fun advance(
        stream: String,
        cursor: Long,
    ) {
        pending[stream] = cursor
    }

    @Synchronized
    override fun flushIfDirty() {
        if (pending.isEmpty()) return
        flushInternal()
    }

    @Synchronized
    override fun flushNow() {
        flushInternal()
    }

    @Synchronized
    override fun load(stream: String): Long? =
        pending[stream]
            ?: db.queryLong(
                "SELECT cursor_value FROM ${IrisDatabaseSchema.CHECKPOINT_TABLE} WHERE stream = ?",
                stream,
            )

    private fun flushInternal() {
        if (pending.isEmpty()) return
        val snapshot = HashMap(pending)
        val now = clock()
        db.inImmediateTransaction {
            for ((stream, cursor) in snapshot) {
                update(
                    """INSERT INTO ${IrisDatabaseSchema.CHECKPOINT_TABLE} (stream, cursor_value, updated_at)
                       VALUES (?, ?, ?)
                       ON CONFLICT(stream) DO UPDATE SET
                           cursor_value = excluded.cursor_value,
                           updated_at = excluded.updated_at""",
                    listOf(stream, cursor, now),
                )
            }
        }
        for ((stream, flushedCursor) in snapshot) {
            if (pending[stream] == flushedCursor) {
                pending.remove(stream)
            }
        }
    }
}
