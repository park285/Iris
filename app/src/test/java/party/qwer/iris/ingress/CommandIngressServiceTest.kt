package party.qwer.iris.ingress

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import party.qwer.iris.ChatLogRepository
import party.qwer.iris.ConfigProvider
import party.qwer.iris.KakaoDB
import party.qwer.iris.SseEventBus
import party.qwer.iris.delivery.webhook.RoutingCommand
import party.qwer.iris.delivery.webhook.RoutingGateway
import party.qwer.iris.delivery.webhook.RoutingResult
import party.qwer.iris.model.RoomEventRecord
import party.qwer.iris.nativecore.NativeCoreHolder
import party.qwer.iris.nativecore.NativeCoreJniBridge
import party.qwer.iris.nativecore.NativeCoreRuntime
import party.qwer.iris.persistence.BatchedCheckpointJournal
import party.qwer.iris.persistence.CheckpointJournal
import party.qwer.iris.persistence.InMemoryMemberIdentityStateStore
import party.qwer.iris.persistence.RoomEventStore
import party.qwer.iris.snapshot.SnapshotEventEmitter
import party.qwer.iris.storage.ChatId
import party.qwer.iris.storage.UserId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CommandIngressServiceTest {
    private val config =
        object : ConfigProvider {
            override val botId = 0L
            override val botName = ""
            override val botSocketPort = 0
            override val inboundSigningSecret = ""
            override val outboundWebhookToken = ""
            override val botControlToken = ""
            override val dbPollingRate = 100L
            override val messageSendRate = 0L
            override val messageSendJitterMax = 0L

            override fun webhookEndpointFor(route: String): String = ""
        }

    @Test
    fun `checkChange initializes lastLogId on first call`() {
        val db = FakeChatLogRepository(latestLogId = 42L)
        val markDirtyCalls = mutableListOf<Long>()
        val ingress =
            CommandIngressService(
                db = db,
                config = config,
                checkpointJournal = FakeCheckpointJournal(),
                routingGateway = FakeRoutingGateway(),
                dispatchDispatcher = Dispatchers.Unconfined,
                onMarkDirty = { chatId -> markDirtyCalls.add(chatId) },
            )

        ingress.checkChange()

        assertEquals(42L, ingress.lastObservedLogId())
        assertEquals(42L, ingress.lastPolledLogId())
        assertEquals(42L, ingress.lastBufferedLogId())
        assertEquals(null, db.lastPolledAfterLogId)
        assertTrue(markDirtyCalls.isEmpty())
        runBlocking { ingress.closeSuspend() }
    }

    @Test
    fun `checkChange loads persisted lastLogId from journal`() {
        val db = FakeChatLogRepository(latestLogId = 100L)
        val store = FakeCheckpointStore(initial = mapOf("chat_logs" to 5L))
        val journal = BatchedCheckpointJournal(store = store, flushIntervalMs = Long.MAX_VALUE, clock = { 0L })
        val ingress =
            CommandIngressService(
                db = db,
                config = config,
                checkpointJournal = journal,
                routingGateway = FakeRoutingGateway(),
                dispatchDispatcher = Dispatchers.Unconfined,
                onMarkDirty = {},
            )

        ingress.checkChange()
        ingress.checkChange()

        assertEquals(5L, ingress.lastObservedLogId())
        assertEquals(5L, ingress.lastPolledLogId())
        assertEquals(5L, ingress.lastBufferedLogId())
        assertEquals(5L, db.lastPolledAfterLogId)
        runBlocking { ingress.closeSuspend() }
    }

    @Test
    fun `checkChange advances without flushing durable store before batching interval`() {
        var currentTime = 1_000L
        val store = FakeCheckpointStore(initial = mapOf("chat_logs" to 10L))
        val journal = BatchedCheckpointJournal(store = store, flushIntervalMs = 5_000L, clock = { currentTime })
        val ingress =
            CommandIngressService(
                db =
                    FakeChatLogRepository(
                        latestLogId = 10L,
                        polledLogs = listOf(webhookChatLogEntry(id = 11L, chatId = 100L, message = "!ping")),
                    ),
                config = config,
                checkpointJournal = journal,
                routingGateway = FakeRoutingGateway(result = RoutingResult.ACCEPTED),
                dispatchDispatcher = Dispatchers.Unconfined,
                onMarkDirty = {},
            )

        ingress.checkChange()
        ingress.checkChange()

        assertEquals(11L, ingress.lastObservedLogId())
        assertEquals(10L, store.load("chat_logs"))

        currentTime = 7_000L
        ingress.checkChange()

        assertEquals(10L, store.load("chat_logs"))
        journal.flushIfDirty()
        assertEquals(11L, store.load("chat_logs"))
        runBlocking { ingress.closeSuspend() }
    }

    @Test
    fun `checkChange emits markDirty for each new log entry`() {
        val db =
            FakeChatLogRepository(
                latestLogId = 10L,
                polledLogs =
                    listOf(
                        webhookChatLogEntry(id = 11L, chatId = 100L, message = "hello"),
                        webhookChatLogEntry(id = 12L, chatId = 200L, message = "!ping"),
                        webhookChatLogEntry(id = 13L, chatId = 100L, message = "/pong"),
                    ),
            )
        val markDirtyCalls = mutableListOf<Long>()
        val ingress =
            CommandIngressService(
                db = db,
                config = config,
                checkpointJournal = FakeCheckpointJournal(initial = mapOf("chat_logs" to 10L)),
                routingGateway = FakeRoutingGateway(),
                dispatchDispatcher = Dispatchers.Unconfined,
                onMarkDirty = { chatId -> markDirtyCalls.add(chatId) },
            )

        ingress.checkChange()
        ingress.checkChange()

        assertEquals(listOf(100L, 200L, 100L), markDirtyCalls)
        runBlocking { ingress.closeSuspend() }
    }

    @Test
    fun `checkChange advances checkpoint after processing`() {
        val journal = FakeCheckpointJournal(initial = mapOf("chat_logs" to 10L))
        val ingress =
            CommandIngressService(
                db =
                    FakeChatLogRepository(
                        latestLogId = 10L,
                        polledLogs = listOf(webhookChatLogEntry(id = 11L, chatId = 100L, message = "!ping")),
                    ),
                config = config,
                checkpointJournal = journal,
                routingGateway = FakeRoutingGateway(result = RoutingResult.ACCEPTED),
                dispatchDispatcher = Dispatchers.Unconfined,
                onMarkDirty = {},
            )

        ingress.checkChange()
        ingress.checkChange()

        assertEquals(11L, journal.saved["chat_logs"])
        assertEquals(listOf("chat_logs" to 11L), journal.advanceCalls)
        runBlocking { ingress.closeSuspend() }
    }

    @Test
    fun `checkChange batch decrypts polled message fields before dispatch`() =
        runTest {
            val jni = FakeNativeCoreJni(listOf("!first", "not command", "plain-attachment"))
            val runtime =
                NativeCoreRuntime.create(
                    env = mapOf("IRIS_NATIVE_CORE" to "on", "IRIS_NATIVE_ROUTING" to "on"),
                    loader = {},
                    jni = jni,
                )
            val gateway = FakeRoutingGateway()
            val ingress =
                CommandIngressService(
                    db =
                        FakeChatLogRepository(
                            latestLogId = 10L,
                            polledLogs =
                                listOf(
                                    webhookChatLogEntry(id = 11L, chatId = 100L, message = "encrypted-first"),
                                    webhookChatLogEntry(
                                        id = 12L,
                                        chatId = 100L,
                                        message = "encrypted-image",
                                    ).copy(
                                        messageType = "2",
                                        attachment = "encrypted-attachment",
                                    ),
                                ),
                        ),
                    config = config,
                    checkpointJournal = FakeCheckpointJournal(initial = mapOf("chat_logs" to 10L)),
                    routingGateway = gateway,
                    dispatchDispatcher = StandardTestDispatcher(testScheduler),
                    onMarkDirty = {},
                )

            try {
                NativeCoreHolder.install(runtime)

                ingress.checkChange()
                ingress.checkChange()
                runCurrent()

                assertEquals(1, jni.decryptCalls)
                assertEquals(3, jni.lastDecryptItemCount)
                assertEquals(1, jni.routingCalls)
                assertEquals(2, jni.lastRoutingItemCount)
                assertEquals(listOf("!first", "not command"), gateway.commands.map { it.text })
                assertEquals("plain-attachment", gateway.commands[1].attachment)
                val decryptStats =
                    runtime
                        .diagnostics()
                        .componentStats
                        .getValue("decrypt")
                assertEquals(1L, decryptStats.jniCalls)
                assertEquals(3L, decryptStats.items)
            } finally {
                ingress.closeSuspend()
                NativeCoreHolder.resetForTest()
            }
        }

    @Test
    fun `non command message can emit nickname change from ingress`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val eventStore = RecordingIngressRoomEventStore()
            val stateStore =
                InMemoryMemberIdentityStateStore().apply {
                    save(ChatId(100L), mapOf(UserId(200L) to "New Name"))
                }
            eventStore.insert(
                chatId = 100L,
                eventType = "nickname_change",
                userId = 200L,
                payload = """{"chatId":100,"userId":200,"oldNickname":"Old Name","newNickname":"Old Name","timestamp":1}""",
                createdAtMs = 1L,
            )
            val db =
                FakeChatLogRepository(
                    latestLogId = 10L,
                    polledLogs = listOf(webhookChatLogEntry(id = 11L, chatId = 100L, message = "hello", userId = 200L)),
                    roomMetadata = KakaoDB.RoomMetadata(type = "OM", linkId = "300"),
                    senderNames = mapOf(200L to "New Name"),
                )
            val ingress =
                CommandIngressService(
                    db = db,
                    config = config,
                    checkpointJournal = FakeCheckpointJournal(initial = mapOf("chat_logs" to 10L)),
                    memberIdentityStateStore = stateStore,
                    roomEventStore = eventStore,
                    nicknameEventEmitter = SnapshotEventEmitter(SseEventBus(bufferSize = 8), FakeRoutingGateway(), eventStore),
                    routingGateway = FakeRoutingGateway(),
                    dispatchDispatcher = dispatcher,
                    onMarkDirty = {},
                )

            ingress.checkChange()
            ingress.checkChange()
            runCurrent()
            advanceTimeBy(1_100L)
            runCurrent()

            val event = eventStore.insertedEvents.last { it.eventType == "nickname_change" }
            assertTrue(event.payload.contains("\"oldNickname\":\"Old Name\""))
            assertTrue(event.payload.contains("\"newNickname\":\"New Name\""))
            ingress.closeSuspend()
        }

    @Test
    fun `invalid metadata still observes nickname change from ingress`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val eventStore = RecordingIngressRoomEventStore()
            val stateStore =
                InMemoryMemberIdentityStateStore().apply {
                    save(ChatId(100L), mapOf(UserId(200L) to "Old Name"))
                }
            val db =
                FakeChatLogRepository(
                    latestLogId = 10L,
                    polledLogs =
                        listOf(
                            KakaoDB.ChatLogEntry(
                                id = 11L,
                                chatId = 100L,
                                userId = 200L,
                                message = "hello",
                                metadata = "{broken",
                                createdAt = "1711111111",
                            ),
                        ),
                    roomMetadata = KakaoDB.RoomMetadata(type = "OM", linkId = "300"),
                    senderNames = mapOf(200L to "New Name"),
                )
            val ingress =
                CommandIngressService(
                    db = db,
                    config = config,
                    checkpointJournal = FakeCheckpointJournal(initial = mapOf("chat_logs" to 10L)),
                    memberIdentityStateStore = stateStore,
                    roomEventStore = eventStore,
                    nicknameEventEmitter = SnapshotEventEmitter(SseEventBus(bufferSize = 8), FakeRoutingGateway(), eventStore),
                    routingGateway = FakeRoutingGateway(),
                    dispatchDispatcher = dispatcher,
                    onMarkDirty = {},
                )

            ingress.checkChange()
            ingress.checkChange()
            runCurrent()
            advanceTimeBy(1_100L)
            runCurrent()

            val event = eventStore.insertedEvents.single { it.eventType == "nickname_change" }
            assertTrue(event.payload.contains("\"oldNickname\":\"Old Name\""))
            assertTrue(event.payload.contains("\"newNickname\":\"New Name\""))
            ingress.closeSuspend()
        }

    @Test
    fun `non open room does not emit nickname change from ingress`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val eventStore = RecordingIngressRoomEventStore()
            val stateStore =
                InMemoryMemberIdentityStateStore().apply {
                    save(ChatId(100L), mapOf(UserId(200L) to "Old Name"))
                }
            val db =
                FakeChatLogRepository(
                    latestLogId = 10L,
                    polledLogs = listOf(webhookChatLogEntry(id = 11L, chatId = 100L, message = "hello", userId = 200L)),
                    roomMetadata = KakaoDB.RoomMetadata(type = "OD", linkId = ""),
                    senderNames = mapOf(200L to "New Name"),
                )
            val ingress =
                CommandIngressService(
                    db = db,
                    config = config,
                    checkpointJournal = FakeCheckpointJournal(initial = mapOf("chat_logs" to 10L)),
                    memberIdentityStateStore = stateStore,
                    roomEventStore = eventStore,
                    nicknameEventEmitter = SnapshotEventEmitter(SseEventBus(bufferSize = 8), FakeRoutingGateway(), eventStore),
                    routingGateway = FakeRoutingGateway(),
                    dispatchDispatcher = dispatcher,
                    onMarkDirty = {},
                )

            ingress.checkChange()
            ingress.checkChange()
            runCurrent()
            advanceTimeBy(1_100L)
            runCurrent()

            assertEquals(0, eventStore.insertedEvents.count { it.eventType == "nickname_change" })
            ingress.closeSuspend()
        }

    @Test
    fun `nickname lookup failure does not stop ingress dispatch`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            var resolveCalls = 0
            val routingGateway = FakeRoutingGateway()
            val ingress =
                CommandIngressService(
                    db =
                        FakeChatLogRepository(
                            latestLogId = 10L,
                            polledLogs = listOf(webhookChatLogEntry(id = 11L, chatId = 100L, message = "!ping", userId = 200L)),
                            roomMetadata = KakaoDB.RoomMetadata(type = "OM", linkId = "300"),
                            senderNameProvider = {
                                resolveCalls++
                                if (resolveCalls == 1) {
                                    error("lookup failed")
                                }
                                "Recovered Name"
                            },
                        ),
                    config = config,
                    checkpointJournal = FakeCheckpointJournal(initial = mapOf("chat_logs" to 10L)),
                    memberIdentityStateStore = InMemoryMemberIdentityStateStore(),
                    roomEventStore = RecordingIngressRoomEventStore(),
                    nicknameEventEmitter = SnapshotEventEmitter(SseEventBus(bufferSize = 8), FakeRoutingGateway(), RecordingIngressRoomEventStore()),
                    routingGateway = routingGateway,
                    dispatchDispatcher = dispatcher,
                    onMarkDirty = {},
                )

            ingress.checkChange()
            ingress.checkChange()
            runCurrent()

            assertEquals(1, routingGateway.commands.size)
            assertEquals(11L, ingress.lastObservedLogId())
            ingress.closeSuspend()
        }

    @Test
    fun `ingress nickname tracker prefers stored baseline over stale alerted nickname`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val eventStore = RecordingIngressRoomEventStore()
            val stateStore =
                InMemoryMemberIdentityStateStore().apply {
                    save(ChatId(100L), mapOf(UserId(200L) to "Old Name"))
                }
            eventStore.insert(
                chatId = 100L,
                eventType = "nickname_change",
                userId = 200L,
                payload = """{"chatId":100,"userId":200,"oldNickname":"Old Name","newNickname":"Stale Alert","timestamp":1}""",
                createdAtMs = 1L,
            )
            val db =
                FakeChatLogRepository(
                    latestLogId = 10L,
                    polledLogs = listOf(webhookChatLogEntry(id = 11L, chatId = 100L, message = "hello", userId = 200L)),
                    roomMetadata = KakaoDB.RoomMetadata(type = "OM", linkId = "300"),
                    senderNames = mapOf(200L to "New Name"),
                )
            val ingress =
                CommandIngressService(
                    db = db,
                    config = config,
                    checkpointJournal = FakeCheckpointJournal(initial = mapOf("chat_logs" to 10L)),
                    memberIdentityStateStore = stateStore,
                    roomEventStore = eventStore,
                    nicknameEventEmitter = SnapshotEventEmitter(SseEventBus(bufferSize = 8), FakeRoutingGateway(), eventStore),
                    routingGateway = FakeRoutingGateway(),
                    dispatchDispatcher = dispatcher,
                    onMarkDirty = {},
                )

            ingress.checkChange()
            ingress.checkChange()
            runCurrent()
            advanceTimeBy(1_100L)
            runCurrent()

            val event = eventStore.insertedEvents.last { it.eventType == "nickname_change" }
            assertTrue(event.payload.contains("\"oldNickname\":\"Old Name\""))
            assertTrue(event.payload.contains("\"newNickname\":\"New Name\""))
            ingress.closeSuspend()
        }

    @Test
    fun `ingress does not emit when nickname candidate flips during rechecks`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val eventStore = RecordingIngressRoomEventStore()
            val stateStore =
                InMemoryMemberIdentityStateStore().apply {
                    save(ChatId(100L), mapOf(UserId(200L) to "추천공장겜"))
                }
            var resolveCalls = 0
            val db =
                FakeChatLogRepository(
                    latestLogId = 10L,
                    polledLogs = listOf(webhookChatLogEntry(id = 11L, chatId = 100L, message = "hello", userId = 200L)),
                    roomMetadata = KakaoDB.RoomMetadata(type = "OM", linkId = "300"),
                    senderNameProvider = {
                        resolveCalls += 1
                        when (resolveCalls) {
                            1 -> "방장봇"
                            2 -> "추천공장겜"
                            else -> "추천공장겜"
                        }
                    },
                )
            val ingress =
                CommandIngressService(
                    db = db,
                    config = config,
                    checkpointJournal = FakeCheckpointJournal(initial = mapOf("chat_logs" to 10L)),
                    memberIdentityStateStore = stateStore,
                    roomEventStore = eventStore,
                    nicknameEventEmitter = SnapshotEventEmitter(SseEventBus(bufferSize = 8), FakeRoutingGateway(), eventStore),
                    routingGateway = FakeRoutingGateway(),
                    dispatchDispatcher = dispatcher,
                    onMarkDirty = {},
                )

            ingress.checkChange()
            ingress.checkChange()
            runCurrent()
            advanceTimeBy(1_100L)
            runCurrent()

            assertEquals(0, eventStore.insertedEvents.count { it.eventType == "nickname_change" })
            ingress.closeSuspend()
        }

    @Test
    fun `ingress retries nickname recheck so delayed table updates are caught without extra command`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val eventStore = RecordingIngressRoomEventStore()
            val stateStore =
                InMemoryMemberIdentityStateStore().apply {
                    save(ChatId(100L), mapOf(UserId(200L) to "Old Name"))
                }
            var resolveCalls = 0
            val db =
                FakeChatLogRepository(
                    latestLogId = 10L,
                    polledLogs = listOf(webhookChatLogEntry(id = 11L, chatId = 100L, message = "hello", userId = 200L)),
                    roomMetadata = KakaoDB.RoomMetadata(type = "OM", linkId = "300"),
                    senderNameProvider = {
                        resolveCalls += 1
                        if (resolveCalls == 1) "Old Name" else "New Name"
                    },
                )
            val ingress =
                CommandIngressService(
                    db = db,
                    config = config,
                    checkpointJournal = FakeCheckpointJournal(initial = mapOf("chat_logs" to 10L)),
                    memberIdentityStateStore = stateStore,
                    roomEventStore = eventStore,
                    nicknameEventEmitter = SnapshotEventEmitter(SseEventBus(bufferSize = 8), FakeRoutingGateway(), eventStore),
                    routingGateway = FakeRoutingGateway(),
                    dispatchDispatcher = dispatcher,
                    onMarkDirty = {},
                )

            ingress.checkChange()
            ingress.checkChange()
            advanceTimeBy(3_100L)
            runCurrent()

            val event = eventStore.insertedEvents.single { it.eventType == "nickname_change" }
            assertTrue(event.payload.contains("\"oldNickname\":\"Old Name\""))
            assertTrue(event.payload.contains("\"newNickname\":\"New Name\""))
            ingress.closeSuspend()
        }

    @Test
    fun `ingress retries nickname recheck when room metadata lookup temporarily fails`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val eventStore = RecordingIngressRoomEventStore()
            val stateStore =
                InMemoryMemberIdentityStateStore().apply {
                    save(ChatId(100L), mapOf(UserId(200L) to "Old Name"))
                }
            var metadataCalls = 0
            val db =
                FakeChatLogRepository(
                    latestLogId = 10L,
                    polledLogs = listOf(webhookChatLogEntry(id = 11L, chatId = 100L, message = "hello", userId = 200L)),
                    roomMetadataProvider = {
                        metadataCalls += 1
                        if (metadataCalls == 1) {
                            error("metadata unavailable")
                        }
                        KakaoDB.RoomMetadata(type = "OM", linkId = "300")
                    },
                    senderNames = mapOf(200L to "New Name"),
                )
            val ingress =
                CommandIngressService(
                    db = db,
                    config = config,
                    checkpointJournal = FakeCheckpointJournal(initial = mapOf("chat_logs" to 10L)),
                    memberIdentityStateStore = stateStore,
                    roomEventStore = eventStore,
                    nicknameEventEmitter = SnapshotEventEmitter(SseEventBus(bufferSize = 8), FakeRoutingGateway(), eventStore),
                    routingGateway = FakeRoutingGateway(),
                    dispatchDispatcher = dispatcher,
                    onMarkDirty = {},
                )

            ingress.checkChange()
            ingress.checkChange()
            runCurrent()
            advanceTimeBy(3_100L)
            runCurrent()

            val event = eventStore.insertedEvents.single { it.eventType == "nickname_change" }
            assertTrue(event.payload.contains("\"oldNickname\":\"Old Name\""))
            assertTrue(event.payload.contains("\"newNickname\":\"New Name\""))
            ingress.closeSuspend()
        }

    @Test
    fun `ingress schedules delayed room rechecks after a log entry`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val markDirtyCalls = mutableListOf<Long>()
            val ingress =
                CommandIngressService(
                    db =
                        FakeChatLogRepository(
                            latestLogId = 10L,
                            polledLogs = listOf(webhookChatLogEntry(id = 11L, chatId = 100L, message = "hello", userId = 200L)),
                            roomMetadata = KakaoDB.RoomMetadata(type = "OM", linkId = "300"),
                            senderNames = mapOf(200L to "Name"),
                        ),
                    config = config,
                    checkpointJournal = FakeCheckpointJournal(initial = mapOf("chat_logs" to 10L)),
                    memberIdentityStateStore = InMemoryMemberIdentityStateStore(),
                    roomEventStore = RecordingIngressRoomEventStore(),
                    nicknameEventEmitter = SnapshotEventEmitter(SseEventBus(bufferSize = 8), FakeRoutingGateway(), RecordingIngressRoomEventStore()),
                    routingGateway = FakeRoutingGateway(),
                    dispatchDispatcher = dispatcher,
                    onMarkDirty = { chatId -> markDirtyCalls.add(chatId) },
                )

            ingress.checkChange()
            ingress.checkChange()
            advanceTimeBy(3_100L)
            runCurrent()

            assertTrue(markDirtyCalls.count { it == 100L } >= 4)
            ingress.closeSuspend()
        }

    @Test
    fun `non open room still schedules delayed room rechecks`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val markDirtyCalls = mutableListOf<Long>()
            val ingress =
                CommandIngressService(
                    db =
                        FakeChatLogRepository(
                            latestLogId = 10L,
                            polledLogs = listOf(webhookChatLogEntry(id = 11L, chatId = 100L, message = "hello", userId = 200L)),
                            roomMetadata = KakaoDB.RoomMetadata(type = "OD", linkId = ""),
                            senderNames = mapOf(200L to "Name"),
                        ),
                    config = config,
                    checkpointJournal = FakeCheckpointJournal(initial = mapOf("chat_logs" to 10L)),
                    memberIdentityStateStore = InMemoryMemberIdentityStateStore(),
                    roomEventStore = RecordingIngressRoomEventStore(),
                    nicknameEventEmitter = SnapshotEventEmitter(SseEventBus(bufferSize = 8), FakeRoutingGateway(), RecordingIngressRoomEventStore()),
                    routingGateway = FakeRoutingGateway(),
                    dispatchDispatcher = dispatcher,
                    onMarkDirty = { chatId -> markDirtyCalls.add(chatId) },
                )

            ingress.checkChange()
            ingress.checkChange()
            advanceTimeBy(3_100L)
            runCurrent()

            assertTrue(markDirtyCalls.count { it == 100L } >= 4)
            ingress.closeSuspend()
        }

    @Test
    fun `checkChange does not flush checkpoints from ingress hot path`() {
        val journal = FakeCheckpointJournal(initial = mapOf("chat_logs" to 10L))
        val ingress =
            CommandIngressService(
                db =
                    FakeChatLogRepository(
                        latestLogId = 10L,
                        polledLogs = listOf(webhookChatLogEntry(id = 11L, chatId = 100L, message = "!ping")),
                    ),
                config = config,
                checkpointJournal = journal,
                routingGateway = FakeRoutingGateway(result = RoutingResult.ACCEPTED),
                dispatchDispatcher = Dispatchers.Unconfined,
                onMarkDirty = {},
            )

        ingress.checkChange()
        ingress.checkChange()

        assertEquals(0, journal.flushIfDirtyCalls)
        runBlocking { ingress.closeSuspend() }
    }

    @Test
    fun `quiet polling produces no markDirty calls`() {
        val markDirtyCalls = mutableListOf<Long>()
        val ingress =
            CommandIngressService(
                db = FakeChatLogRepository(latestLogId = 10L),
                config = config,
                checkpointJournal = FakeCheckpointJournal(initial = mapOf("chat_logs" to 10L)),
                routingGateway = FakeRoutingGateway(),
                dispatchDispatcher = Dispatchers.Unconfined,
                onMarkDirty = { chatId -> markDirtyCalls.add(chatId) },
            )

        repeat(3) { ingress.checkChange() }

        assertTrue(markDirtyCalls.isEmpty())
        runBlocking { ingress.closeSuspend() }
    }

    @Test
    fun `checkChange keeps routing batch off polling caller while dispatch is pending`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val db =
                FakeChatLogRepository(
                    latestLogId = 10L,
                    polledLogs =
                        listOf(
                            webhookChatLogEntry(id = 11L, chatId = 100L, message = "!ping"),
                            webhookChatLogEntry(id = 12L, chatId = 100L, message = "!pong"),
                        ),
                )
            val routingGateway = FakeRoutingGateway(result = RoutingResult.ACCEPTED)
            val ingress =
                CommandIngressService(
                    db = db,
                    config = config,
                    checkpointJournal = FakeCheckpointJournal(initial = mapOf("chat_logs" to 10L)),
                    routingGateway = routingGateway,
                    dispatchDispatcher = dispatcher,
                    onMarkDirty = {},
                )

            ingress.checkChange()
            ingress.checkChange()
            ingress.checkChange()

            assertEquals(2, db.pollCalls)
            assertTrue(routingGateway.commands.isEmpty())
            assertEquals(10L, ingress.lastObservedLogId())
            assertEquals(12L, ingress.lastPolledLogId())
            assertEquals(12L, ingress.lastBufferedLogId())

            runCurrent()

            assertEquals(2, routingGateway.commands.size)
            assertEquals(12L, ingress.lastObservedLogId())
            ingress.closeSuspend()
        }

    @Test
    fun `dispatch partition routes pending commands through one routeBatch call`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val gateway = FakeRoutingGateway(result = RoutingResult.ACCEPTED)
            val ingress =
                CommandIngressService(
                    db =
                        FakeChatLogRepository(
                            latestLogId = 10L,
                            polledLogs =
                                listOf(
                                    webhookChatLogEntry(id = 11L, chatId = 100L, message = "!first"),
                                    webhookChatLogEntry(id = 12L, chatId = 100L, message = "!second"),
                                ),
                        ),
                    config = config,
                    checkpointJournal = FakeCheckpointJournal(initial = mapOf("chat_logs" to 10L)),
                    routingGateway = gateway,
                    dispatchDispatcher = dispatcher,
                    partitioningPolicy = IngressPartitioningPolicy(partitionCount = 1, partitionQueueCapacity = 8),
                    onMarkDirty = {},
                )

            ingress.checkChange()
            ingress.checkChange()
            runCurrent()

            assertEquals(1, gateway.routeBatchCalls)
            assertEquals(listOf(11L, 12L), gateway.commandBatches.single().map { it.sourceLogId })
            assertEquals(listOf(11L, 12L), gateway.commands.map { it.sourceLogId })
            assertEquals(12L, ingress.lastObservedLogId())
            ingress.closeSuspend()
        }

    @Test
    fun `routeBatch retry leaves tail pending for the next drain`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val gateway =
                FakeRoutingGateway().apply {
                    batchResults = listOf(RoutingResult.ACCEPTED, RoutingResult.RETRY_LATER)
                }
            val ingress =
                CommandIngressService(
                    db =
                        FakeChatLogRepository(
                            latestLogId = 10L,
                            polledLogs =
                                listOf(
                                    webhookChatLogEntry(id = 11L, chatId = 100L, message = "!first"),
                                    webhookChatLogEntry(id = 12L, chatId = 100L, message = "!retry"),
                                    webhookChatLogEntry(id = 13L, chatId = 100L, message = "!tail"),
                                ),
                        ),
                    config = config,
                    checkpointJournal = FakeCheckpointJournal(initial = mapOf("chat_logs" to 10L)),
                    routingGateway = gateway,
                    dispatchDispatcher = dispatcher,
                    partitioningPolicy = IngressPartitioningPolicy(partitionCount = 1, partitionQueueCapacity = 8),
                    onMarkDirty = {},
                )

            ingress.checkChange()
            ingress.checkChange()
            runCurrent()

            assertEquals(1, gateway.routeBatchCalls)
            assertEquals(listOf(11L, 12L, 13L), gateway.commandBatches.single().map { it.sourceLogId })
            assertEquals(listOf(11L, 12L), gateway.commands.map { it.sourceLogId })
            assertEquals(
                IngressProgressSnapshot(
                    lastPolledLogId = 13L,
                    lastBufferedLogId = 13L,
                    lastCommittedLogId = 11L,
                    bufferedCount = 2,
                    blockedPartitionCount = 1,
                    activeDispatchCount = 0,
                    pendingByPartition = listOf(1),
                    blockedLogIds = listOf(12L),
                ),
                ingress.progressSnapshot(),
            )

            gateway.batchResults = listOf(RoutingResult.ACCEPTED)
            ingress.checkChange()
            runCurrent()

            assertEquals(listOf(11L, 12L, 12L, 13L), gateway.commands.map { it.sourceLogId })
            assertEquals(13L, ingress.lastObservedLogId())
            ingress.closeSuspend()
        }

    @Test
    fun `partitioned dispatch keeps polling lane non blocking while a route is busy`() =
        runTest {
            val db =
                FakeChatLogRepository(
                    latestLogId = 10L,
                    polledLogs = listOf(webhookChatLogEntry(id = 11L, chatId = 100L, message = "!ping")),
                )
            val ingress =
                CommandIngressService(
                    db = db,
                    config = config,
                    checkpointJournal = FakeCheckpointJournal(initial = mapOf("chat_logs" to 10L)),
                    routingGateway = FakeRoutingGateway(result = RoutingResult.RETRY_LATER),
                    dispatchDispatcher = StandardTestDispatcher(testScheduler),
                    partitioningPolicy = IngressPartitioningPolicy(partitionCount = 1, partitionQueueCapacity = 8),
                    onMarkDirty = {},
                )

            ingress.checkChange()
            ingress.checkChange()
            runCurrent()

            assertEquals(10L, ingress.lastObservedLogId())
            assertEquals(11L, ingress.lastPolledLogId())
            assertEquals(11L, ingress.lastBufferedLogId())

            ingress.checkChange()
            assertEquals(2, db.pollCalls)
            assertEquals(11L, db.lastPolledAfterLogId)
            ingress.closeSuspend()
        }

    @Test
    fun `bounded dispatch buffer stops polling only when capacity is saturated`() =
        runTest {
            val db =
                FakeChatLogRepository(
                    latestLogId = 10L,
                    polledLogs = listOf(webhookChatLogEntry(id = 11L, chatId = 100L, message = "!ping")),
                )
            val ingress =
                CommandIngressService(
                    db = db,
                    config = config,
                    checkpointJournal = FakeCheckpointJournal(initial = mapOf("chat_logs" to 10L)),
                    routingGateway = FakeRoutingGateway(result = RoutingResult.RETRY_LATER),
                    dispatchDispatcher = StandardTestDispatcher(testScheduler),
                    partitioningPolicy = IngressPartitioningPolicy(partitionCount = 1, partitionQueueCapacity = 1, maxBufferedDispatches = 1),
                    onMarkDirty = {},
                )

            ingress.checkChange()
            ingress.checkChange()
            runCurrent()

            assertEquals(10L, ingress.lastObservedLogId())
            assertEquals(1, db.pollCalls)
            assertEquals(11L, ingress.lastPolledLogId())
            assertEquals(11L, ingress.lastBufferedLogId())

            ingress.checkChange()

            assertEquals(1, db.pollCalls)
            ingress.closeSuspend()
        }

    @Test
    fun `later logs keep buffering while an earlier partition is blocked`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val db =
                FakeChatLogRepository(
                    latestLogId = 10L,
                    polledLogs =
                        listOf(
                            webhookChatLogEntry(id = 11L, chatId = 100L, message = "!first"),
                            webhookChatLogEntry(id = 12L, chatId = 101L, message = "!second"),
                            webhookChatLogEntry(id = 13L, chatId = 103L, message = "!third"),
                        ),
                )
            val routingGateway =
                object : RoutingGateway {
                    override fun route(command: RoutingCommand): RoutingResult =
                        if (command.sourceLogId == 11L) {
                            RoutingResult.RETRY_LATER
                        } else {
                            RoutingResult.ACCEPTED
                        }

                    override fun close() {}
                }
            val ingress =
                CommandIngressService(
                    db = db,
                    config = config,
                    checkpointJournal = FakeCheckpointJournal(initial = mapOf("chat_logs" to 10L)),
                    routingGateway = routingGateway,
                    dispatchDispatcher = dispatcher,
                    partitioningPolicy = IngressPartitioningPolicy(partitionCount = 2, partitionQueueCapacity = 1, maxBufferedDispatches = 4),
                    onMarkDirty = {},
                )

            ingress.checkChange()
            ingress.checkChange()
            runCurrent()

            assertEquals(10L, ingress.lastObservedLogId())
            assertEquals(13L, ingress.lastPolledLogId())
            assertEquals(13L, ingress.lastBufferedLogId())

            ingress.checkChange()
            assertEquals(2, db.pollCalls)
            assertEquals(13L, db.lastPolledAfterLogId)
            assertEquals(
                IngressProgressSnapshot(
                    lastPolledLogId = 13L,
                    lastBufferedLogId = 13L,
                    lastCommittedLogId = 10L,
                    bufferedCount = 3,
                    blockedPartitionCount = 1,
                    activeDispatchCount = 0,
                    pendingByPartition = listOf(0, 0),
                    blockedLogIds = listOf(11L),
                ),
                ingress.progressSnapshot(),
            )
            ingress.closeSuspend()
        }

    @Test
    fun `partitioned dispatch commits checkpoints in log order even when later partition finishes first`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val allowFirstLog = AtomicBoolean(false)
            val routeCalls = ConcurrentHashMap<Long, AtomicInteger>()
            val journal = FakeCheckpointJournal(initial = mapOf("chat_logs" to 10L))
            val ingress =
                CommandIngressService(
                    db =
                        FakeChatLogRepository(
                            latestLogId = 10L,
                            polledLogs =
                                listOf(
                                    webhookChatLogEntry(id = 11L, chatId = 100L, message = "!first"),
                                    webhookChatLogEntry(id = 12L, chatId = 101L, message = "!second"),
                                ),
                        ),
                    config = config,
                    checkpointJournal = journal,
                    routingGateway =
                        object : RoutingGateway {
                            override fun route(command: RoutingCommand): RoutingResult {
                                routeCalls.computeIfAbsent(command.sourceLogId) { AtomicInteger(0) }.incrementAndGet()
                                return when (command.sourceLogId) {
                                    11L -> if (allowFirstLog.get()) RoutingResult.ACCEPTED else RoutingResult.RETRY_LATER
                                    12L -> RoutingResult.ACCEPTED
                                    else -> error("unexpected sourceLogId=${command.sourceLogId}")
                                }
                            }

                            override fun close() {}
                        },
                    dispatchDispatcher = dispatcher,
                    partitioningPolicy = IngressPartitioningPolicy(partitionCount = 2, partitionQueueCapacity = 8),
                    onMarkDirty = {},
                )

            ingress.checkChange()
            ingress.checkChange()
            runCurrent()

            assertEquals(10L, ingress.lastObservedLogId())
            assertEquals(12L, ingress.lastPolledLogId())
            assertEquals(10L, journal.saved["chat_logs"])
            assertEquals(1, routeCalls[12L]?.get())
            assertNotNull(routeCalls[11L])

            ingress.checkChange()
            runCurrent()

            assertEquals(10L, ingress.lastObservedLogId())
            assertEquals(1, routeCalls[12L]?.get())
            assertTrue(routeCalls[11L]!!.get() >= 2)

            allowFirstLog.set(true)
            ingress.checkChange()
            runCurrent()

            assertEquals(12L, ingress.lastObservedLogId())
            assertEquals(listOf("chat_logs" to 11L, "chat_logs" to 12L), journal.advanceCalls.takeLast(2))
            assertEquals(1, routeCalls[12L]?.get())
            ingress.closeSuspend()
        }

    @Test
    fun `image message forwards direct thread metadata from db columns`() =
        runTest {
            val gateway = FakeRoutingGateway()
            val db =
                FakeChatLogRepository(
                    latestLogId = 10L,
                    polledLogs =
                        listOf(
                            KakaoDB.ChatLogEntry(
                                id = 12L,
                                chatLogId = "img-log-456",
                                chatId = 100L,
                                userId = 200L,
                                message = "사진",
                                metadata = "{\"enc\":0,\"origin\":\"CHATLOG\"}",
                                createdAt = "1711111112",
                                messageType = "2",
                                threadId = "cmd-log-123",
                                threadScope = 2,
                                attachment = "{\"url\":\"https://example.com/img.jpg\"}",
                            ),
                        ),
                )
            val ingress =
                CommandIngressService(
                    db = db,
                    config = config,
                    checkpointJournal = FakeCheckpointJournal(),
                    routingGateway = gateway,
                    dispatchDispatcher = StandardTestDispatcher(testScheduler),
                    onMarkDirty = {},
                )

            ingress.checkChange()
            ingress.checkChange()
            testScheduler.runCurrent()

            val commands = gateway.commands
            assertEquals(1, commands.size, "Expected 1 routed image command")

            val imageCmd = commands[0]
            assertEquals("2", imageCmd.messageType)
            assertEquals("cmd-log-123", imageCmd.threadId)
            assertEquals(2, imageCmd.threadScope)
            ingress.closeSuspend()
        }

    @Test
    fun `close cancels dispatch scope`() {
        val ingress =
            CommandIngressService(
                db = FakeChatLogRepository(latestLogId = 10L),
                config = config,
                checkpointJournal = FakeCheckpointJournal(initial = mapOf("chat_logs" to 10L)),
                routingGateway = FakeRoutingGateway(),
                dispatchDispatcher = Dispatchers.Unconfined,
                onMarkDirty = {},
            )

        val dispatchScope =
            CommandIngressService::class.java.getDeclaredField("dispatchScope").let { field ->
                field.isAccessible = true
                field.get(ingress) as kotlinx.coroutines.CoroutineScope
            }
        val job = checkNotNull(dispatchScope.coroutineContext[Job])
        assertTrue(job.isActive)

        runBlocking { ingress.closeSuspend() }

        assertTrue(job.isCancelled)
    }

    @Test
    fun `close waits for dispatch loop jobs to finish`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val ingress =
                CommandIngressService(
                    db = FakeChatLogRepository(latestLogId = 10L),
                    config = config,
                    checkpointJournal = FakeCheckpointJournal(initial = mapOf("chat_logs" to 10L)),
                    routingGateway = FakeRoutingGateway(),
                    dispatchDispatcher = dispatcher,
                    onMarkDirty = {},
                )

            val jobs =
                CommandIngressService::class.java.getDeclaredField("dispatchLoopJobs").let { field ->
                    field.isAccessible = true
                    @Suppress("UNCHECKED_CAST")
                    field.get(ingress) as List<Job>
                }

            ingress.closeSuspend()

            assertTrue(jobs.all { it.isCompleted }, "close should wait for dispatch loop jobs to complete")
        }
}

private class FakeChatLogRepository(
    var latestLogId: Long = 1L,
    var polledLogs: List<KakaoDB.ChatLogEntry> = emptyList(),
    var roomMetadata: KakaoDB.RoomMetadata = KakaoDB.RoomMetadata(),
    var roomMetadataProvider: ((Long) -> KakaoDB.RoomMetadata)? = null,
    var senderNames: Map<Long, String> = emptyMap(),
    var senderNameProvider: ((Long) -> String)? = null,
) : ChatLogRepository {
    var lastPolledAfterLogId: Long? = null
    var pollCalls: Int = 0

    override fun pollChatLogsAfter(
        afterLogId: Long,
        limit: Int,
    ): List<KakaoDB.ChatLogEntry> {
        pollCalls++
        lastPolledAfterLogId = afterLogId
        return polledLogs.filter { it.id > afterLogId }.take(limit)
    }

    override fun resolveSenderName(userId: Long): String = senderNameProvider?.invoke(userId) ?: senderNames[userId] ?: userId.toString()

    override fun resolveRoomMetadata(chatId: Long): KakaoDB.RoomMetadata = roomMetadataProvider?.invoke(chatId) ?: roomMetadata

    override fun latestLogId(): Long = latestLogId
}

private class RecordingIngressRoomEventStore : RoomEventStore {
    data class InsertedEvent(
        val chatId: Long,
        val eventType: String,
        val userId: Long,
        val payload: String,
        val createdAtMs: Long,
    )

    val insertedEvents = CopyOnWriteArrayList<InsertedEvent>()

    override fun insert(
        chatId: Long,
        eventType: String,
        userId: Long,
        payload: String,
        createdAtMs: Long,
    ): Long {
        insertedEvents += InsertedEvent(chatId, eventType, userId, payload, createdAtMs)
        return insertedEvents.size.toLong()
    }

    override fun listByChatId(
        chatId: Long,
        limit: Int,
        afterId: Long,
    ): List<RoomEventRecord> =
        insertedEvents
            .mapIndexed { index, event ->
                RoomEventRecord(
                    id = index + 1L,
                    chatId = event.chatId,
                    eventType = event.eventType,
                    userId = event.userId,
                    payload = event.payload,
                    createdAt = event.createdAtMs,
                )
            }.filter { it.chatId == chatId && it.id > afterId }
            .take(limit)

    override fun maxId(): Long = 0

    override fun pruneOlderThan(cutoffMs: Long): Int = 0
}

private class FakeCheckpointStore(
    initial: Map<String, Long> = emptyMap(),
) : party.qwer.iris.CheckpointStore {
    private val values = initial.toMutableMap()

    override fun load(streamName: String): Long? = values[streamName]

    override fun save(
        streamName: String,
        lastLogId: Long,
    ) {
        values[streamName] = lastLogId
    }
}

private class FakeCheckpointJournal(
    initial: Map<String, Long> = emptyMap(),
) : CheckpointJournal {
    val saved = initial.toMutableMap()
    val advanceCalls = mutableListOf<Pair<String, Long>>()
    var flushIfDirtyCalls = 0

    override fun advance(
        stream: String,
        cursor: Long,
    ) {
        saved[stream] = cursor
        advanceCalls += stream to cursor
    }

    override fun flushIfDirty() {
        flushIfDirtyCalls++
    }

    override fun flushNow() {}

    override fun load(stream: String): Long? = saved[stream]
}

private class FakeRoutingGateway(
    var result: RoutingResult = RoutingResult.ACCEPTED,
) : RoutingGateway {
    val commands = mutableListOf<RoutingCommand>()
    val commandBatches = mutableListOf<List<RoutingCommand>>()
    var routeBatchCalls = 0
    var batchResults: List<RoutingResult>? = null

    override fun route(command: RoutingCommand): RoutingResult {
        commands += command
        return result
    }

    override fun routeBatch(commands: List<RoutingCommand>): List<RoutingResult> {
        routeBatchCalls += 1
        commandBatches += commands
        val configuredResults = batchResults
        if (configuredResults != null) {
            val results = configuredResults.take(commands.size)
            commands.take(results.size).forEachIndexed { index, command ->
                this.commands += command
                if (results[index] == RoutingResult.RETRY_LATER) {
                    return results.take(index + 1)
                }
            }
            return results
        }
        val results = mutableListOf<RoutingResult>()
        for (command in commands) {
            val routed = route(command)
            results += routed
            if (routed == RoutingResult.RETRY_LATER) {
                break
            }
        }
        return results
    }

    override fun close() {}
}

private class FakeNativeCoreJni(
    private val decryptResults: List<String>,
) : NativeCoreJniBridge {
    var decryptCalls = 0
        private set
    var lastDecryptItemCount = 0
        private set
    var routingCalls = 0
        private set
    var lastRoutingItemCount = 0
        private set

    override fun nativeSelfTest(): String = "iris-native-core:test"

    override fun decryptBatch(requestJsonBytes: ByteArray): ByteArray {
        decryptCalls++
        lastDecryptItemCount = Regex(""""encType"""").findAll(requestJsonBytes.decodeToString()).count()
        val items =
            decryptResults.joinToString(separator = ",") { result ->
                """{"ok":true,"plaintext":"$result"}"""
            }
        return """{"items":[$items]}""".encodeToByteArray()
    }

    override fun routingBatch(requestJsonBytes: ByteArray): ByteArray {
        routingCalls++
        val messages = Regex(""""text":"(.*?)"""").findAll(requestJsonBytes.decodeToString()).map { it.groupValues[1] }.toList()
        lastRoutingItemCount = messages.size
        val items =
            messages.joinToString(separator = ",") { rawMessage ->
                val message = rawMessage.replace("\\\"", "\"")
                val kind =
                    when {
                        message.startsWith("//") -> "COMMENT"
                        message.startsWith("!") || message.startsWith("/") -> "WEBHOOK"
                        else -> "NONE"
                    }
                """{"ok":true,"kind":"$kind","normalizedText":"${message.jsonEscaped()}"}"""
            }
        return """{"items":[$items]}""".encodeToByteArray()
    }
}

private fun String.jsonEscaped(): String =
    replace("\\", "\\\\")
        .replace("\"", "\\\"")

private fun webhookChatLogEntry(
    id: Long,
    chatId: Long,
    message: String,
    userId: Long = 200L,
    createdAt: String = "1711111111",
): KakaoDB.ChatLogEntry =
    KakaoDB.ChatLogEntry(
        id = id,
        chatId = chatId,
        userId = userId,
        message = message,
        metadata = "{\"enc\":0,\"origin\":\"CHATLOG\"}",
        createdAt = createdAt,
    )
