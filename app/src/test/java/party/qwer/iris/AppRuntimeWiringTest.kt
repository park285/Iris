package party.qwer.iris

import party.qwer.iris.persistence.IrisDatabaseSchema
import party.qwer.iris.persistence.JdbcSqliteHelper
import party.qwer.iris.persistence.PendingWebhookDelivery
import party.qwer.iris.storage.ChatId
import party.qwer.iris.storage.UserId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AppRuntimeWiringTest {
    private companion object {
        private const val CHAT_LOGS_STREAM = "chat_logs"
    }

    @Test
    fun `persistence factory creates runtime graph with batched checkpoint journal`() {
        var currentTime = 1_000L
        val driver = JdbcSqliteHelper.inMemory()

        val runtime =
            PersistenceFactory.createSqliteRuntime(
                driver = driver,
                checkpointFlushIntervalMs = 5_000L,
                clock = { currentTime },
            )

        val deliveryId =
            runtime.webhookOutboxStore.enqueue(
                PendingWebhookDelivery(
                    messageId = "runtime-wiring-msg",
                    roomId = 7L,
                    route = "default",
                    payloadJson = "{}",
                ),
            )
        assertTrue(deliveryId > 0)

        runtime.checkpointJournal.advance(CHAT_LOGS_STREAM, 123L)
        assertEquals(123L, runtime.checkpointJournal.load(CHAT_LOGS_STREAM))
        assertEquals(
            null,
            driver.queryLong(
                "SELECT cursor_value FROM ${IrisDatabaseSchema.CHECKPOINT_TABLE} WHERE stream = ?",
                CHAT_LOGS_STREAM,
            ),
        )

        currentTime = 7_000L
        runtime.checkpointJournal.flushIfDirty()

        assertEquals(
            123L,
            driver.queryLong(
                "SELECT cursor_value FROM ${IrisDatabaseSchema.CHECKPOINT_TABLE} WHERE stream = ?",
                CHAT_LOGS_STREAM,
            ),
        )
    }

    @Test
    fun `snapshot runtime factory uses current room ids and member snapshots`() {
        val expectedRoomIds = listOf(ChatId(11L), ChatId(22L))
        val snapshots =
            mapOf(
                ChatId(11L) to snapshotData(chatId = 11L),
                ChatId(22L) to snapshotData(chatId = 22L),
            )

        val reader =
            SnapshotRuntimeFactory.createRoomSnapshotReader(
                listRoomChatIds = { expectedRoomIds },
                snapshot = { chatId -> snapshots.getValue(chatId) },
            )

        assertEquals(expectedRoomIds, reader.listRoomChatIds())
        assertEquals(snapshots.getValue(ChatId(11L)), reader.snapshot(ChatId(11L)))
        assertEquals(snapshots.getValue(ChatId(22L)), reader.snapshot(ChatId(22L)))
    }

    @Test
    fun `shutdown plan flushes checkpoint before closing persistence driver`() {
        val calls = mutableListOf<String>()

        val plan =
            RuntimeBuilders.buildShutdownPlan(
                RuntimeBuilders.ShutdownHooks(
                    stopServer = { calls += "server" },
                    stopDbObserver = { calls += "dbObserver" },
                    stopSnapshotObserver = { calls += "snapshotObserver" },
                    stopProfileIndexer = { calls += "profileIndexer" },
                    stopImageDeleter = { calls += "imageDeleter" },
                    closeWebhookOutbox = { calls += "webhookOutbox" },
                    closeIngress = { calls += "ingress" },
                    cancelSnapshotScope = { calls += "snapshotScope" },
                    shutdownReplyService = { calls += "replyService" },
                    stopBridgeHealthCache = { calls += "bridgeHealthCache" },
                    persistConfig = { calls += "persistConfig" },
                    flushCheckpointJournal = { calls += "flushCheckpoint" },
                    closePersistenceDriver = { calls += "closePersistenceDriver" },
                    closeKakaoDb = { calls += "closeKakaoDb" },
                ),
            )

        RuntimeBuilders.runShutdownPlan(plan)

        assertTrue(calls.indexOf("flushCheckpoint") < calls.indexOf("closePersistenceDriver"))
        assertTrue(calls.indexOf("closePersistenceDriver") < calls.indexOf("closeKakaoDb"))
        assertNotNull(plan.firstOrNull { it.name == "flushCheckpointJournal" })
    }

    private fun snapshotData(chatId: Long): RoomSnapshotData =
        RoomSnapshotData(
            chatId = ChatId(chatId),
            linkId = null,
            memberIds = setOf(UserId(chatId)),
            blindedIds = emptySet(),
            nicknames = mapOf(UserId(chatId) to "room-$chatId"),
            roles = mapOf(UserId(chatId) to 0),
            profileImages = emptyMap(),
        )
}
