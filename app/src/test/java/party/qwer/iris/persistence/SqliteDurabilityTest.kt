package party.qwer.iris.persistence

import party.qwer.iris.PersistenceFactory
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SqliteDurabilityTest {
    private companion object {
        private const val CHAT_LOGS_STREAM = "chat_logs"
    }

    @Test
    fun `webhook delivery survives close and reopen`() {
        withTempDatabase("iris-durability-webhook") { dbFile ->
            val helper1 = JdbcSqliteHelper.fileBacked(dbFile.absolutePath)
            IrisDatabaseSchema.createWebhookOutboxTable(helper1)
            val store1 = SqliteWebhookDeliveryStore(helper1)
            val id =
                store1.enqueue(
                    PendingWebhookDelivery(
                        messageId = "durable-msg-1",
                        roomId = 42L,
                        route = "default",
                        payloadJson = "{\"test\":\"durability\"}",
                    ),
                )
            assertTrue(id > 0)
            helper1.close()

            val helper2 = JdbcSqliteHelper.fileBacked(dbFile.absolutePath)
            IrisDatabaseSchema.createWebhookOutboxTable(helper2)
            val store2 = SqliteWebhookDeliveryStore(helper2)
            val claimed = store2.claimReady(limit = 10)
            assertEquals(1, claimed.size)
            assertEquals("durable-msg-1", claimed.single().messageId)
            assertEquals(42L, claimed.single().roomId)
            assertEquals("{\"test\":\"durability\"}", claimed.single().payloadJson)
            helper2.close()
        }
    }

    @Test
    fun `checkpoint cursor survives close and reopen`() {
        withTempDatabase("iris-durability-checkpoint") { dbFile ->
            val helper1 = JdbcSqliteHelper.fileBacked(dbFile.absolutePath)
            IrisDatabaseSchema.createCheckpointTable(helper1)
            val journal1 = SqliteCheckpointJournal(helper1)
            journal1.advance("chat_logs", 999L)
            journal1.advance("snapshots", 500L)
            journal1.flushNow()
            helper1.close()

            val helper2 = JdbcSqliteHelper.fileBacked(dbFile.absolutePath)
            IrisDatabaseSchema.createCheckpointTable(helper2)
            val journal2 = SqliteCheckpointJournal(helper2)
            assertEquals(999L, journal2.load("chat_logs"))
            assertEquals(500L, journal2.load("snapshots"))
            helper2.close()
        }
    }

    @Test
    fun `claimed delivery recovery after crash simulation`() {
        withTempDatabase("iris-durability-crash") { dbFile ->
            val helper1 = JdbcSqliteHelper.fileBacked(dbFile.absolutePath)
            IrisDatabaseSchema.createWebhookOutboxTable(helper1)
            var now = 1000L
            val store1 = SqliteWebhookDeliveryStore(helper1, clock = { now })
            store1.enqueue(
                PendingWebhookDelivery("crash-msg-1", 1L, "default", "{}"),
            )
            store1.claimReady(limit = 10)
            helper1.close()

            now = 62_000L
            val helper2 = JdbcSqliteHelper.fileBacked(dbFile.absolutePath)
            IrisDatabaseSchema.createWebhookOutboxTable(helper2)
            val store2 = SqliteWebhookDeliveryStore(helper2, clock = { now })
            val recovered = store2.recoverExpiredClaims(olderThanMs = 60_000L)
            assertEquals(1, recovered)

            val reclaimed = store2.claimReady(limit = 10)
            assertEquals(1, reclaimed.size)
            assertEquals("crash-msg-1", reclaimed.single().messageId)
            helper2.close()
        }
    }

    @Test
    fun `batched checkpoint persists only after flushed write across reopen`() {
        withTempDatabase("iris-durability-batched-checkpoint") { dbFile ->
            var currentTime = 1_000L

            val helper1 = JdbcSqliteHelper.fileBacked(dbFile.absolutePath)
            val runtime1 =
                PersistenceFactory.createSqliteRuntime(
                    driver = helper1,
                    checkpointFlushIntervalMs = 5_000L,
                    clock = { currentTime },
                )
            runtime1.checkpointJournal.advance(CHAT_LOGS_STREAM, 111L)
            helper1.close()

            val helper2 = JdbcSqliteHelper.fileBacked(dbFile.absolutePath)
            IrisDatabaseSchema.createCheckpointTable(helper2)
            val journalAfterUnflushedClose = SqliteCheckpointJournal(helper2)
            assertNull(journalAfterUnflushedClose.load(CHAT_LOGS_STREAM))
            helper2.close()

            currentTime = 2_000L
            val helper3 = JdbcSqliteHelper.fileBacked(dbFile.absolutePath)
            val runtime2 =
                PersistenceFactory.createSqliteRuntime(
                    driver = helper3,
                    checkpointFlushIntervalMs = 5_000L,
                    clock = { currentTime },
                )
            runtime2.checkpointJournal.advance(CHAT_LOGS_STREAM, 222L)
            currentTime = 7_000L
            runtime2.checkpointJournal.flushIfDirty()
            helper3.close()

            val helper4 = JdbcSqliteHelper.fileBacked(dbFile.absolutePath)
            IrisDatabaseSchema.createCheckpointTable(helper4)
            val journalAfterFlushedClose = SqliteCheckpointJournal(helper4)
            assertEquals(222L, journalAfterFlushedClose.load(CHAT_LOGS_STREAM))
            helper4.close()
        }
    }

    private fun withTempDatabase(
        prefix: String,
        block: (File) -> Unit,
    ) {
        val dbFile = File.createTempFile(prefix, ".db")
        try {
            block(dbFile)
        } finally {
            dbFile.delete()
            File(dbFile.absolutePath + "-wal").delete()
            File(dbFile.absolutePath + "-shm").delete()
        }
    }
}
