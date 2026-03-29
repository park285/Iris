package party.qwer.iris.persistence

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SqliteDurabilityTest {
    @Test
    fun `webhook delivery survives close and reopen`() {
        val dbFile = File.createTempFile("iris-durability-webhook", ".db")
        try {
            // Write phase
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

            // Reopen phase
            val helper2 = JdbcSqliteHelper.fileBacked(dbFile.absolutePath)
            IrisDatabaseSchema.createWebhookOutboxTable(helper2)
            val store2 = SqliteWebhookDeliveryStore(helper2)
            val claimed = store2.claimReady(limit = 10)
            assertEquals(1, claimed.size)
            assertEquals("durable-msg-1", claimed.single().messageId)
            assertEquals(42L, claimed.single().roomId)
            assertEquals("{\"test\":\"durability\"}", claimed.single().payloadJson)
            helper2.close()
        } finally {
            dbFile.delete()
            File(dbFile.absolutePath + "-wal").delete()
            File(dbFile.absolutePath + "-shm").delete()
        }
    }

    @Test
    fun `checkpoint cursor survives close and reopen`() {
        val dbFile = File.createTempFile("iris-durability-checkpoint", ".db")
        try {
            // Write phase
            val helper1 = JdbcSqliteHelper.fileBacked(dbFile.absolutePath)
            IrisDatabaseSchema.createCheckpointTable(helper1)
            val journal1 = SqliteCheckpointJournal(helper1)
            journal1.advance("chat_logs", 999L)
            journal1.advance("snapshots", 500L)
            journal1.flushNow()
            helper1.close()

            // Reopen phase
            val helper2 = JdbcSqliteHelper.fileBacked(dbFile.absolutePath)
            IrisDatabaseSchema.createCheckpointTable(helper2)
            val journal2 = SqliteCheckpointJournal(helper2)
            assertEquals(999L, journal2.load("chat_logs"))
            assertEquals(500L, journal2.load("snapshots"))
            helper2.close()
        } finally {
            dbFile.delete()
            File(dbFile.absolutePath + "-wal").delete()
            File(dbFile.absolutePath + "-shm").delete()
        }
    }

    @Test
    fun `claimed delivery recovery after crash simulation`() {
        val dbFile = File.createTempFile("iris-durability-crash", ".db")
        try {
            // Pre-crash: enqueue and claim
            val helper1 = JdbcSqliteHelper.fileBacked(dbFile.absolutePath)
            IrisDatabaseSchema.createWebhookOutboxTable(helper1)
            var now = 1000L
            val store1 = SqliteWebhookDeliveryStore(helper1, clock = { now })
            store1.enqueue(
                PendingWebhookDelivery("crash-msg-1", 1L, "default", "{}"),
            )
            store1.claimReady(limit = 10)
            helper1.close()

            // Post-crash: reopen and recover
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
        } finally {
            dbFile.delete()
            File(dbFile.absolutePath + "-wal").delete()
            File(dbFile.absolutePath + "-shm").delete()
        }
    }
}
