package party.qwer.iris.persistence

import party.qwer.iris.CheckpointStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BatchedCheckpointJournalTest {
    @Test
    fun `advance does not flush immediately`() {
        val store = InMemoryCheckpointStore()
        val journal =
            BatchedCheckpointJournal(
                store = store,
                flushIntervalMs = 5000L,
                clock = { 0L },
            )

        journal.advance("chat_logs", 100L)

        assertNull(store.load("chat_logs"))
    }

    @Test
    fun `flushNow persists all pending advances`() {
        val store = InMemoryCheckpointStore()
        val journal =
            BatchedCheckpointJournal(
                store = store,
                flushIntervalMs = 5000L,
                clock = { 0L },
            )

        journal.advance("chat_logs", 100L)
        journal.advance("chat_logs", 200L)
        journal.advance("snapshots", 50L)
        journal.flushNow()

        assertEquals(200L, store.load("chat_logs"))
        assertEquals(50L, store.load("snapshots"))
    }

    @Test
    fun `flushIfDirty does not flush before interval`() {
        var currentTime = 1000L
        val store = InMemoryCheckpointStore()
        val journal =
            BatchedCheckpointJournal(
                store = store,
                flushIntervalMs = 5000L,
                clock = { currentTime },
            )

        journal.advance("chat_logs", 100L)
        journal.flushIfDirty()

        assertNull(store.load("chat_logs"))
    }

    @Test
    fun `flushIfDirty flushes after interval elapsed`() {
        var currentTime = 1000L
        val store = InMemoryCheckpointStore()
        val journal =
            BatchedCheckpointJournal(
                store = store,
                flushIntervalMs = 5000L,
                clock = { currentTime },
            )

        journal.advance("chat_logs", 100L)
        currentTime = 7000L
        journal.flushIfDirty()

        assertEquals(100L, store.load("chat_logs"))
    }

    @Test
    fun `load delegates to underlying store`() {
        val store = InMemoryCheckpointStore()
        store.save("chat_logs", 42L)
        val journal =
            BatchedCheckpointJournal(
                store = store,
                flushIntervalMs = 5000L,
                clock = { 0L },
            )

        assertEquals(42L, journal.load("chat_logs"))
    }

    @Test
    fun `load returns pending value over store value`() {
        val store = InMemoryCheckpointStore()
        store.save("chat_logs", 42L)
        val journal =
            BatchedCheckpointJournal(
                store = store,
                flushIntervalMs = 5000L,
                clock = { 0L },
            )

        journal.advance("chat_logs", 100L)

        assertEquals(100L, journal.load("chat_logs"))
    }

    @Test
    fun `flushNow resets timer so next flushIfDirty waits full interval`() {
        var currentTime = 0L
        val store = InMemoryCheckpointStore()
        val journal =
            BatchedCheckpointJournal(
                store = store,
                flushIntervalMs = 5000L,
                clock = { currentTime },
            )

        journal.advance("chat_logs", 100L)
        journal.flushNow()
        assertEquals(100L, store.load("chat_logs"))

        journal.advance("chat_logs", 200L)
        currentTime = 3000L
        journal.flushIfDirty()

        assertEquals(100L, store.load("chat_logs"))

        currentTime = 6000L
        journal.flushIfDirty()

        assertEquals(200L, store.load("chat_logs"))
    }

    @Test
    fun `advance with no pending changes makes flushNow a no-op`() {
        val store = InMemoryCheckpointStore()
        val journal =
            BatchedCheckpointJournal(
                store = store,
                flushIntervalMs = 5000L,
                clock = { 0L },
            )

        journal.flushNow()

        assertNull(store.load("chat_logs"))
    }
}

private class InMemoryCheckpointStore : CheckpointStore {
    private val data = mutableMapOf<String, Long>()

    override fun load(streamName: String): Long? = data[streamName]

    override fun save(
        streamName: String,
        lastLogId: Long,
    ) {
        data[streamName] = lastLogId
    }
}
