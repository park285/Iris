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
    private val delegate: CheckpointJournal,
    private val flushIntervalMs: Long = 5_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) : CheckpointJournal {
    constructor(
        store: CheckpointStore,
        flushIntervalMs: Long = 5_000L,
        clock: () -> Long = System::currentTimeMillis,
    ) : this(
        delegate = StoreBackedCheckpointJournal(store),
        flushIntervalMs = flushIntervalMs,
        clock = clock,
    )

    private var dirty = false
    private var lastFlushAtMs: Long = clock()

    @Synchronized
    override fun advance(
        stream: String,
        cursor: Long,
    ) {
        delegate.advance(stream, cursor)
        dirty = true
    }

    @Synchronized
    override fun flushIfDirty() {
        val now = clock()
        if (!dirty) return
        if (now - lastFlushAtMs < flushIntervalMs) return
        delegate.flushIfDirty()
        dirty = false
        lastFlushAtMs = now
    }

    @Synchronized
    override fun flushNow() {
        delegate.flushNow()
        dirty = false
        lastFlushAtMs = clock()
    }

    @Synchronized
    override fun load(stream: String): Long? = delegate.load(stream)
}

private class StoreBackedCheckpointJournal(
    private val store: CheckpointStore,
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
    }
}
