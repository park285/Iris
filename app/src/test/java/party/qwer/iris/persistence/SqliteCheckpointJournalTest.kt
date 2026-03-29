package party.qwer.iris.persistence

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SqliteCheckpointJournalTest {
    @Test
    fun `createAll initializes sqlite connection pragmas before schema creation`() {
        val helper = RecordingSqliteDriver()

        IrisDatabaseSchema.createAll(helper)

        assertEquals(
            listOf(
                "PRAGMA journal_mode=WAL",
                "PRAGMA busy_timeout=5000",
                "CREATE TABLE IF NOT EXISTS ${IrisDatabaseSchema.WEBHOOK_OUTBOX_TABLE} (\n    id INTEGER PRIMARY KEY AUTOINCREMENT,\n    message_id TEXT NOT NULL UNIQUE,\n    room_id INTEGER NOT NULL,\n    route TEXT NOT NULL,\n    payload_json TEXT NOT NULL,\n    status TEXT NOT NULL DEFAULT 'PENDING',\n    attempt_count INTEGER NOT NULL DEFAULT 0,\n    next_attempt_at INTEGER NOT NULL DEFAULT 0,\n    claim_token TEXT,\n    claimed_at INTEGER,\n    created_at INTEGER NOT NULL,\n    updated_at INTEGER NOT NULL,\n    last_error TEXT\n)",
                "CREATE INDEX IF NOT EXISTS idx_webhook_outbox_ready\nON ${IrisDatabaseSchema.WEBHOOK_OUTBOX_TABLE} (status, next_attempt_at, id)",
                "CREATE TABLE IF NOT EXISTS ${IrisDatabaseSchema.CHECKPOINT_TABLE} (\n    stream TEXT PRIMARY KEY,\n    cursor_value INTEGER NOT NULL,\n    updated_at INTEGER NOT NULL\n)",
            ),
            helper.executedSql,
        )
    }

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

    @Test
    fun `flush keeps newly advanced cursor when stream changes during flush`() {
        CoordinatedSqliteDriver(JdbcSqliteHelper.inMemory()).use { helper ->
            IrisDatabaseSchema.createCheckpointTable(helper)
            val journal = SqliteCheckpointJournal(helper)

            journal.advance("chat_logs", 1L)
            val flushThread =
                thread(
                    start = true,
                ) {
                    journal.flushNow()
                }

            assertTrue(
                helper.awaitSnapshot(5, TimeUnit.SECONDS),
            )
            val advanceThread =
                thread(
                    start = true,
                ) {
                    journal.advance("chat_logs", 100L)
                }
            helper.continueFlush()
            flushThread.join()
            advanceThread.join()

            assertEquals(100L, journal.load("chat_logs"))
        }
    }
}

private class RecordingSqliteDriver : SqliteDriver {
    val executedSql = mutableListOf<String>()

    override fun execute(sql: String) {
        executedSql += sql
    }

    override fun queryLong(
        sql: String,
        vararg args: Any?,
    ): Long? = error("Not used in this test")

    override fun <T> query(
        sql: String,
        args: List<Any?>,
        mapRow: (SqliteRow) -> T,
    ): List<T> = error("Not used in this test")

    override fun update(
        sql: String,
        args: List<Any?>,
    ): Int = error("Not used in this test")

    override fun <T> inImmediateTransaction(block: SqliteDriver.() -> T): T = block(this)

    override fun close() = Unit
}

private class CoordinatedSqliteDriver(
    private val delegate: SqliteDriver,
) : SqliteDriver {
    private val snapshotTaken = CountDownLatch(1)
    private val allowFlushToContinue = CountDownLatch(1)
    private var updateCount = 0

    override fun execute(sql: String) {
        delegate.execute(sql)
    }

    override fun queryLong(
        sql: String,
        vararg args: Any?,
    ): Long? = delegate.queryLong(sql, *args)

    override fun <T> query(
        sql: String,
        args: List<Any?>,
        mapRow: (SqliteRow) -> T,
    ): List<T> = delegate.query(sql, args, mapRow)

    override fun update(
        sql: String,
        args: List<Any?>,
    ): Int {
        updateCount += 1
        if (updateCount == 1) {
            snapshotTaken.countDown()
            check(allowFlushToContinue.await(5, TimeUnit.SECONDS)) {
                "Timed out waiting to continue coordinated flush"
            }
        }
        return delegate.update(sql, args)
    }

    override fun <T> inImmediateTransaction(block: SqliteDriver.() -> T): T =
        delegate.inImmediateTransaction {
            block(this@CoordinatedSqliteDriver)
        }

    override fun close() {
        delegate.close()
    }

    fun awaitSnapshot(
        timeout: Long,
        unit: TimeUnit,
    ): Boolean = snapshotTaken.await(timeout, unit)

    fun continueFlush() {
        allowFlushToContinue.countDown()
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
