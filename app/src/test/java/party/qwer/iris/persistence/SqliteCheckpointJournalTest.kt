package party.qwer.iris.persistence

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SqliteCheckpointJournalTest {
    @Test
    fun `advance then flushNow then load round-trips cursor value`() {
        val (_, journal) = createJournal()

        journal.advance("chat_logs", 42L)
        journal.flushNow()

        assertEquals(42L, journal.load("chat_logs"))
    }

    @Test
    fun `load returns null before any flush`() {
        val helper = JdbcSqliteHelper.inMemory()
        IrisDatabaseSchema.createCheckpointTable(helper)
        val journal = SqliteCheckpointJournal(helper)

        journal.advance("chat_logs", 42L)

        val reloadedJournal = SqliteCheckpointJournal(helper)

        assertNull(reloadedJournal.load("chat_logs"))
    }

    @Test
    fun `multi-stream cursors are independent`() {
        val (_, journal) = createJournal()

        journal.advance("stream_a", 10L)
        journal.advance("stream_b", 20L)
        journal.flushNow()

        assertEquals(10L, journal.load("stream_a"))
        assertEquals(20L, journal.load("stream_b"))
    }

    @Test
    fun `flushIfDirty writes only when dirty`() {
        val (_, journal) = createJournal()

        journal.flushIfDirty()
        assertNull(journal.load("chat_logs"))

        journal.advance("chat_logs", 42L)
        journal.flushIfDirty()
        assertEquals(42L, journal.load("chat_logs"))

        journal.flushIfDirty()
        assertEquals(42L, journal.load("chat_logs"))
    }

    @Test
    fun `multiple advances before flush keep only the latest cursor`() {
        val (_, journal) = createJournal()

        journal.advance("chat_logs", 10L)
        journal.advance("chat_logs", 20L)
        journal.advance("chat_logs", 30L)
        journal.flushNow()

        assertEquals(30L, journal.load("chat_logs"))
    }

    @Test
    fun `flushNow on close persists all dirty cursors`() {
        val helper = JdbcSqliteHelper.inMemory()
        IrisDatabaseSchema.createCheckpointTable(helper)
        val journal = SqliteCheckpointJournal(helper)

        journal.advance("stream_a", 10L)
        journal.advance("stream_b", 20L)
        journal.flushNow()

        val reloadedJournal = SqliteCheckpointJournal(helper)

        assertEquals(10L, reloadedJournal.load("stream_a"))
        assertEquals(20L, reloadedJournal.load("stream_b"))
    }

    @Test
    fun `load returns null for unknown stream`() {
        val (_, journal) = createJournal()

        assertNull(journal.load("nonexistent"))
    }

    @Test
    fun `flush overwrites previous cursor value`() {
        val (_, journal) = createJournal()

        journal.advance("chat_logs", 10L)
        journal.flushNow()
        journal.advance("chat_logs", 99L)
        journal.flushNow()

        assertEquals(99L, journal.load("chat_logs"))
    }
}

private fun createJournal(
    clock: () -> Long = System::currentTimeMillis,
): Pair<JdbcSqliteHelper, SqliteCheckpointJournal> {
    val helper = JdbcSqliteHelper.inMemory()
    IrisDatabaseSchema.createCheckpointTable(helper)
    val journal = SqliteCheckpointJournal(helper, clock)
    return helper to journal
}
