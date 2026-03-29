package party.qwer.iris.persistence

import party.qwer.iris.CheckpointStore

interface CheckpointJournal {
    fun advance(
        stream: String,
        cursor: Long,
    )

    fun flushIfDirty()

    fun flushNow()

    fun load(stream: String): Long?
}

class BatchedCheckpointJournal(
    private val store: CheckpointStore,
    private val flushIntervalMs: Long = 5_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) : CheckpointJournal {
    private val pending = mutableMapOf<String, Long>()
    private var lastFlushAtMs: Long = clock()

    @Synchronized
    override fun advance(
        stream: String,
        cursor: Long,
    ) {
        pending[stream] = cursor
    }

    @Synchronized
    override fun flushIfDirty() {
        val now = clock()
        if (pending.isEmpty()) return
        if (now - lastFlushAtMs < flushIntervalMs) return
        flushInternal()
    }

    @Synchronized
    override fun flushNow() {
        flushInternal()
    }

    @Synchronized
    override fun load(stream: String): Long? = pending[stream] ?: store.load(stream)

    private fun flushInternal() {
        if (pending.isEmpty()) return
        for ((stream, cursor) in pending) {
            store.save(stream, cursor)
        }
        pending.clear()
        lastFlushAtMs = clock()
    }
}
