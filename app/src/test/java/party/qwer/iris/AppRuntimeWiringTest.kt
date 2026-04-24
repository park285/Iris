package party.qwer.iris

import kotlinx.coroutines.runBlocking
import party.qwer.iris.persistence.IrisDatabaseSchema
import party.qwer.iris.persistence.JdbcSqliteHelper
import party.qwer.iris.persistence.PendingWebhookDelivery
import party.qwer.iris.persistence.PersistedSnapshotState
import party.qwer.iris.persistence.SqliteSseEventStore
import party.qwer.iris.snapshot.RoomSnapshotReadResult
import party.qwer.iris.storage.ChatId
import party.qwer.iris.storage.UserId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
        runtime.snapshotStateStore.saveMissingConfirmed(snapshotData(chatId = 77L), confirmedAtMs = 1_000L)
        assertEquals(
            PersistedSnapshotState.MissingConfirmed(
                previousSnapshot = snapshotData(chatId = 77L),
                confirmedAtMs = 1_000L,
            ),
            runtime.snapshotStateStore.loadAll()[ChatId(77L)],
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
                snapshot = { chatId -> RoomSnapshotReadResult.Present(snapshots.getValue(chatId)) },
            )

        assertEquals(expectedRoomIds, reader.listRoomChatIds())
        assertEquals(RoomSnapshotReadResult.Present(snapshots.getValue(ChatId(11L))), reader.snapshot(ChatId(11L)))
        assertEquals(RoomSnapshotReadResult.Present(snapshots.getValue(ChatId(22L))), reader.snapshot(ChatId(22L)))
    }

    @Test
    fun `runtime SSE bus replays persisted events across restart`() {
        val helper = JdbcSqliteHelper.inMemory()
        IrisDatabaseSchema.createSseEventsTable(helper)
        val store = SqliteSseEventStore(helper)

        createRuntimeSseEventBus(store = store, bufferSize = 2, clock = { 1_000L }).use { firstBus ->
            firstBus.emit("session-1", "snapshot")
        }

        createRuntimeSseEventBus(store = store, bufferSize = 2, clock = { 2_000L }).use { secondBus ->
            secondBus.emit("session-2", "member_event")

            val replay = secondBus.replayEnvelopes(0L)
            assertEquals(2, replay.size)
            assertEquals("session-1", replay[0].payload)
            assertEquals("snapshot", replay[0].eventType)
            assertEquals("session-2", replay[1].payload)
            assertEquals("member_event", replay[1].eventType)
        }

        helper.close()
    }

    @Test
    fun `shutdown plan flushes checkpoint before closing persistence driver`() {
        val calls = mutableListOf<String>()

        val runningSnapshot =
            AppRuntimeRunningSnapshot(
                shutdownHooks =
                    RuntimeBuilders.ShutdownHooks(
                        stopServer = { calls += "server" },
                        stopDbObserver = { calls += "dbObserver" },
                        stopSnapshotObserver = { calls += "snapshotObserver" },
                        stopProfileIndexer = { calls += "profileIndexer" },
                        stopImageDeleter = { calls += "imageDeleter" },
                        closeWebhookOutbox = { calls += "webhookOutbox" },
                        closeIngress = { calls += "ingress" },
                        closeSseEventBus = { calls += "sseEventBus" },
                        cancelSnapshotScope = { calls += "snapshotScope" },
                        shutdownReplyService = { calls += "replyService" },
                        stopBridgeHealthCache = { calls += "bridgeHealthCache" },
                        persistConfig = { calls += "persistConfig" },
                        flushCheckpointJournal = { calls += "flushCheckpoint" },
                        closeSnapshotStateStore = { calls += "closeSnapshotStateStore" },
                        closePersistenceDriver = { calls += "closePersistenceDriver" },
                        closeKakaoDb = { calls += "closeKakaoDb" },
                    ),
            )
        val plan = runningSnapshot.shutdownPlan()

        runningSnapshot.runShutdown()

        assertTrue(calls.indexOf("flushCheckpoint") < calls.indexOf("closePersistenceDriver"))
        assertTrue(calls.indexOf("closeSnapshotStateStore") < calls.indexOf("closePersistenceDriver"))
        assertTrue(calls.indexOf("closePersistenceDriver") < calls.indexOf("closeKakaoDb"))
        assertTrue(calls.indexOf("ingress") < calls.indexOf("sseEventBus"))
        assertNotNull(plan.firstOrNull { it.name == "flushCheckpointJournal" })
    }

    @Test
    fun `startup rollback runs deferred steps in reverse order and continues after failure`() {
        val calls = mutableListOf<String>()
        val rollback =
            StartupRollback().also {
                it.defer { calls += "config" }
                it.defer {
                    calls += "reply"
                    error("reply rollback failed")
                }
                it.defer { calls += "server" }
            }

        rollback.run()

        assertEquals(listOf("server", "reply", "config"), calls)
    }

    @Test
    fun `shutdown plan continues after a step failure and reports aggregate error`() {
        val calls = mutableListOf<String>()

        val snapshot =
            AppRuntimeRunningSnapshot(
                shutdownHooks =
                    RuntimeBuilders.ShutdownHooks(
                        stopServer = { calls += "server" },
                        stopDbObserver = {
                            calls += "dbObserver"
                            error("db observer stop failed")
                        },
                        stopSnapshotObserver = { calls += "snapshotObserver" },
                        stopProfileIndexer = { calls += "profileIndexer" },
                        stopImageDeleter = { calls += "imageDeleter" },
                        closeWebhookOutbox = { calls += "webhookOutbox" },
                        closeIngress = { calls += "ingress" },
                        closeSseEventBus = { calls += "sseEventBus" },
                        cancelSnapshotScope = { calls += "snapshotScope" },
                        shutdownReplyService = { calls += "replyService" },
                        stopBridgeHealthCache = { calls += "bridgeHealthCache" },
                        persistConfig = { calls += "persistConfig" },
                        flushCheckpointJournal = { calls += "flushCheckpoint" },
                        closeSnapshotStateStore = { calls += "closeSnapshotStateStore" },
                        closePersistenceDriver = { calls += "closePersistenceDriver" },
                        closeKakaoDb = { calls += "closeKakaoDb" },
                    ),
            )

        val error =
            assertFailsWith<IllegalStateException> {
                snapshot.runShutdown()
            }

        assertTrue("dbObserver" in calls)
        assertTrue("closeKakaoDb" in calls)
        assertTrue(calls.indexOf("closePersistenceDriver") < calls.indexOf("closeKakaoDb"))
        assertTrue(error.message?.contains("shutdown plan failed") == true)
    }

    @Test
    fun `stop before start does not poison later shutdown`() =
        runBlocking {
            val calls = mutableListOf<String>()
            val runtime =
                AppRuntime(
                    runtimeOptions = testRuntimeOptions(),
                    notificationReferer = "ref",
                    startupSnapshotFactory = {
                        AppRuntimeRunningSnapshot(
                            shutdownHooks =
                                RuntimeBuilders.ShutdownHooks(
                                    stopServer = { calls += "server" },
                                    stopDbObserver = { calls += "dbObserver" },
                                    stopSnapshotObserver = { calls += "snapshotObserver" },
                                    stopProfileIndexer = { calls += "profileIndexer" },
                                    stopImageDeleter = { calls += "imageDeleter" },
                                    closeWebhookOutbox = { calls += "webhookOutbox" },
                                    closeIngress = { calls += "ingress" },
                                    closeSseEventBus = { calls += "sseEventBus" },
                                    cancelSnapshotScope = { calls += "snapshotScope" },
                                    shutdownReplyService = { calls += "replyService" },
                                    stopBridgeHealthCache = { calls += "bridgeHealthCache" },
                                    persistConfig = { calls += "persistConfig" },
                                    flushCheckpointJournal = { calls += "flushCheckpoint" },
                                    closeSnapshotStateStore = { calls += "closeSnapshotStateStore" },
                                    closePersistenceDriver = { calls += "closePersistenceDriver" },
                                    closeKakaoDb = { calls += "closeKakaoDb" },
                                ),
                        )
                    },
                )

            runtime.stop()
            assertEquals("NEW", runtime.lifecycleStateForTest())

            runtime.start()
            assertEquals("RUNNING", runtime.lifecycleStateForTest())

            runtime.stop()
            assertEquals("STOPPED", runtime.lifecycleStateForTest())
            assertTrue("flushCheckpoint" in calls)
        }

    @Test
    fun `runtime supervisor orchestrates suspend start and stop`() =
        runBlocking {
            val calls = mutableListOf<String>()
            val addedHooks = mutableListOf<Thread>()
            val removedHooks = mutableListOf<Thread>()
            val runtime =
                AppRuntime(
                    runtimeOptions = testRuntimeOptions(),
                    notificationReferer = "ref",
                    startupSnapshotFactory = {
                        AppRuntimeRunningSnapshot(
                            shutdownHooks =
                                RuntimeBuilders.ShutdownHooks(
                                    stopServer = { calls += "server" },
                                    stopDbObserver = { calls += "dbObserver" },
                                    stopSnapshotObserver = { calls += "snapshotObserver" },
                                    stopProfileIndexer = { calls += "profileIndexer" },
                                    stopImageDeleter = { calls += "imageDeleter" },
                                    closeWebhookOutbox = { calls += "webhookOutbox" },
                                    closeIngress = { calls += "ingress" },
                                    closeSseEventBus = { calls += "sseEventBus" },
                                    cancelSnapshotScope = { calls += "snapshotScope" },
                                    shutdownReplyService = { calls += "replyService" },
                                    stopBridgeHealthCache = { calls += "bridgeHealthCache" },
                                    persistConfig = { calls += "persistConfig" },
                                    flushCheckpointJournal = { calls += "flushCheckpoint" },
                                    closeSnapshotStateStore = { calls += "closeSnapshotStateStore" },
                                    closePersistenceDriver = { calls += "closePersistenceDriver" },
                                    closeKakaoDb = { calls += "closeKakaoDb" },
                                ),
                        )
                    },
                )

            AppRuntimeSupervisor(
                runtime = runtime,
                awaitStopSignal = {},
                addShutdownHook = { hook -> addedHooks += hook },
                removeShutdownHook = { hook -> removedHooks += hook },
            ).runUntilStopped()

            assertEquals(1, addedHooks.size)
            assertEquals(listOf(addedHooks.single()), removedHooks)
            assertEquals("STOPPED", runtime.lifecycleStateForTest())
            assertTrue("flushCheckpoint" in calls)
        }

    @Test
    fun `failed start can be retried`() {
        var attempts = 0
        val runtime =
            AppRuntime(
                runtimeOptions = testRuntimeOptions(),
                notificationReferer = "ref",
                startupSnapshotFactory = {
                    attempts += 1
                    if (attempts == 1) {
                        error("boom")
                    }
                    AppRuntimeRunningSnapshot(
                        shutdownHooks =
                            RuntimeBuilders.ShutdownHooks(
                                stopServer = {},
                                stopDbObserver = {},
                                stopSnapshotObserver = {},
                                stopProfileIndexer = {},
                                stopImageDeleter = {},
                                closeWebhookOutbox = {},
                                closeIngress = {},
                                closeSseEventBus = {},
                                cancelSnapshotScope = {},
                                shutdownReplyService = {},
                                stopBridgeHealthCache = {},
                                persistConfig = {},
                                flushCheckpointJournal = {},
                                closeSnapshotStateStore = {},
                                closePersistenceDriver = {},
                                closeKakaoDb = {},
                            ),
                    )
                },
            )

        runCatching { runtime.start() }
        assertEquals("STOPPED", runtime.lifecycleStateForTest())

        runtime.start()
        assertEquals(2, attempts)
        assertEquals("RUNNING", runtime.lifecycleStateForTest())
    }

    @Test
    fun `stop during starting requests deferred shutdown after startup finishes`() =
        runBlocking {
            val startupEntered = CountDownLatch(1)
            val startupGate = CountDownLatch(1)
            val shutdownObserved = CountDownLatch(1)
            val runtime =
                AppRuntime(
                    runtimeOptions = testRuntimeOptions(),
                    notificationReferer = "ref",
                    startupSnapshotFactory = {
                        startupEntered.countDown()
                        startupGate.await()
                        AppRuntimeRunningSnapshot(
                            shutdownHooks =
                                RuntimeBuilders.ShutdownHooks(
                                    stopServer = {},
                                    stopDbObserver = {},
                                    stopSnapshotObserver = {},
                                    stopProfileIndexer = {},
                                    stopImageDeleter = {},
                                    closeWebhookOutbox = {},
                                    closeIngress = {},
                                    closeSseEventBus = {},
                                    cancelSnapshotScope = {},
                                    shutdownReplyService = {},
                                    stopBridgeHealthCache = {},
                                    persistConfig = {},
                                    flushCheckpointJournal = { shutdownObserved.countDown() },
                                    closeSnapshotStateStore = {},
                                    closePersistenceDriver = {},
                                    closeKakaoDb = {},
                                ),
                        )
                    },
                )

            val startThread = Thread { runtime.start() }
            startThread.start()
            assertTrue(startupEntered.await(1, TimeUnit.SECONDS))
            assertEquals("STARTING", runtime.lifecycleStateForTest())

            val stopThread =
                Thread {
                    runBlocking { runtime.stop() }
                }
            stopThread.start()
            assertEquals("STARTING", runtime.lifecycleStateForTest())

            startupGate.countDown()
            startThread.join(1_000L)
            stopThread.join(1_000L)

            assertTrue(shutdownObserved.await(1, TimeUnit.SECONDS))
            assertEquals("STOPPED", runtime.lifecycleStateForTest())
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

    private fun testRuntimeOptions(): RuntimeOptions =
        RuntimeOptions(
            disableHttp = true,
            bindHost = "127.0.0.1",
            httpWorkerThreads = 1,
            bridgeHealthRefreshMs = 1_000L,
            snapshotFullReconcileIntervalMs = 1_000L,
            chatRoomRefreshEnabled = false,
            chatRoomRefreshIntervalMs = 1_000L,
            chatRoomRefreshOpenDelayMs = 1_000L,
            snapshotMissingTombstoneTtlMs = null,
            roomEventRetentionMs = null,
            imageDeletionIntervalMs = 1_000L,
            imageRetentionMs = 1_000L,
        )
}
