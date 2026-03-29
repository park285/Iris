package party.qwer.iris

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import party.qwer.iris.delivery.webhook.RoutingCommand
import party.qwer.iris.delivery.webhook.RoutingGateway
import party.qwer.iris.delivery.webhook.RoutingResult
import party.qwer.iris.ingress.CommandIngressService
import party.qwer.iris.model.MemberEvent
import party.qwer.iris.persistence.BatchedCheckpointJournal
import party.qwer.iris.snapshot.RoomDiffEngine
import party.qwer.iris.snapshot.RoomSnapshotReader
import party.qwer.iris.snapshot.SnapshotCoordinator
import party.qwer.iris.snapshot.SnapshotEventEmitter
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ObserverHelperSnapshotTest {
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
    fun `checkChange does not run snapshot diff during quiet polling`() {
        val bundle =
            buildHelper(
                checkpointStore = SnapshotTestCheckpointStore(initial = mapOf("chat_logs" to 10L)),
                chatLogRepository = SnapshotTestChatLogRepository(latestLogId = 10L, polledLogs = emptyList()),
                snapshotReader =
                    SnapshotTestSnapshotReader(
                        rooms = listOf(100L),
                        snapshots =
                            mapOf(
                                100L to
                                    listOf(
                                        snapshot(chatId = 100L, members = setOf(1L)),
                                        snapshot(chatId = 100L, members = setOf(1L, 2L)),
                                    ),
                            ),
                    ),
                diffEngine = SnapshotTestCountingDiffEngine(),
            )

        val diffEngine = requireNotNull(bundle.diffEngine)

        repeat(3) { bundle.helper.checkChange() }

        waitUntil { diffEngine.diffCalls == 0 }
        assertEquals(0, diffEngine.diffCalls)
    }

    @Test
    fun `dirty snapshot diff processes only marked rooms`() {
        val bundle =
            buildHelper(
                checkpointStore = SnapshotTestCheckpointStore(initial = mapOf("chat_logs" to 10L)),
                chatLogRepository = SnapshotTestChatLogRepository(latestLogId = 10L),
                snapshotReader =
                    SnapshotTestSnapshotReader(
                        rooms = listOf(100L, 200L),
                        snapshots =
                            mapOf(
                                100L to
                                    listOf(
                                        snapshot(chatId = 100L, members = setOf(1L), nicknames = mapOf(1L to "Alice")),
                                        snapshot(chatId = 100L, members = setOf(1L, 3L), nicknames = mapOf(1L to "Alice", 3L to "Cara")),
                                    ),
                                200L to
                                    listOf(
                                        snapshot(chatId = 200L, members = setOf(2L), nicknames = mapOf(2L to "Bob")),
                                        snapshot(chatId = 200L, members = setOf(2L, 4L), nicknames = mapOf(2L to "Bob", 4L to "Drew")),
                                    ),
                            ),
                    ),
                diffEngine = SnapshotTestCountingDiffEngine(),
            )

        val diffEngine = requireNotNull(bundle.diffEngine)

        bundle.helper.seedSnapshotCache()
        waitUntil { bundle.snapshotReader.snapshotCalls.size == 2 }

        bundle.helper.markRoomDirty(100L)
        bundle.helper.markRoomDirty(100L)
        bundle.helper.markRoomDirty(200L)
        bundle.helper.runDirtySnapshotDiff(maxRoomsPerTick = 1)

        waitUntil { diffEngine.diffedChatIds == listOf(100L) }
        waitUntil { bundle.bus.replayFrom(0).size == 1 }
        waitUntil { bundle.routingGateway.commands.map { it.room } == listOf("100") }
        assertEquals(listOf(100L), diffEngine.diffedChatIds)
        assertEquals(1, bundle.bus.replayFrom(0).size)
        assertEquals(listOf("100"), bundle.routingGateway.commands.map { it.room })

        bundle.helper.runDirtySnapshotDiff(maxRoomsPerTick = 10)

        waitUntil { diffEngine.diffedChatIds == listOf(100L, 200L) }
        waitUntil { bundle.bus.replayFrom(0).size == 2 }
        assertEquals(listOf(100L, 200L), diffEngine.diffedChatIds)
        assertEquals(2, bundle.bus.replayFrom(0).size)
        assertEquals(listOf("100", "200"), bundle.routingGateway.commands.map { it.room })
        assertEquals(listOf(100L, 200L, 100L, 200L), bundle.snapshotReader.snapshotCalls)
        assertTrue(bundle.bus.replayFrom(0).all { (_, payload) -> payload.contains("\"event\":\"join\"") })
    }

    @Test
    fun `markAllRoomsDirty marks all seeded rooms as dirty`() {
        val bundle =
            buildHelper(
                checkpointStore = SnapshotTestCheckpointStore(initial = mapOf("chat_logs" to 10L)),
                chatLogRepository = SnapshotTestChatLogRepository(latestLogId = 10L),
                snapshotReader =
                    SnapshotTestSnapshotReader(
                        rooms = listOf(100L, 200L, 300L),
                        snapshots =
                            mapOf(
                                100L to listOf(snapshot(chatId = 100L, members = setOf(1L)), snapshot(chatId = 100L, members = setOf(1L, 10L))),
                                200L to listOf(snapshot(chatId = 200L, members = setOf(2L)), snapshot(chatId = 200L, members = setOf(2L, 20L))),
                                300L to listOf(snapshot(chatId = 300L, members = setOf(3L)), snapshot(chatId = 300L, members = setOf(3L, 30L))),
                            ),
                    ),
                diffEngine = SnapshotTestCountingDiffEngine(),
            )

        bundle.helper.seedSnapshotCache()
        waitUntil { bundle.snapshotReader.snapshotCalls.size == 3 }

        bundle.helper.markAllRoomsDirty()
        waitUntil { bundle.helper.dirtyRoomCount() == 3 }
        assertEquals(3, bundle.helper.dirtyRoomCount())

        bundle.helper.runDirtySnapshotDiff(maxRoomsPerTick = 10)
        waitUntil { bundle.helper.dirtyRoomCount() == 0 }
        assertEquals(0, bundle.helper.dirtyRoomCount())
    }

    @Test
    fun `profile changes stay quiet during quiet polling`() {
        val bundle =
            buildHelper(
                checkpointStore = SnapshotTestCheckpointStore(initial = mapOf("chat_logs" to 10L)),
                chatLogRepository = SnapshotTestChatLogRepository(latestLogId = 10L, polledLogs = emptyList()),
                snapshotReader =
                    SnapshotTestSnapshotReader(
                        rooms = listOf(100L),
                        snapshots =
                            mapOf(
                                100L to
                                    listOf(
                                        snapshot(
                                            chatId = 100L,
                                            members = setOf(1L),
                                            profileImages = mapOf(1L to "profile-a"),
                                        ),
                                        snapshot(
                                            chatId = 100L,
                                            members = setOf(1L),
                                            profileImages = mapOf(1L to "profile-b"),
                                        ),
                                    ),
                            ),
                    ),
                diffEngine = RoomSnapshotManager(),
            )

        bundle.helper.seedSnapshotCache()
        waitUntil { bundle.snapshotReader.snapshotCalls.size == 1 }

        repeat(3) { bundle.helper.checkChange() }

        assertTrue(bundle.bus.replayFrom(0).isEmpty())
        assertTrue(bundle.routingGateway.commands.isEmpty())
        assertEquals(1, bundle.snapshotReader.snapshotCalls.size)
    }

    @Test
    fun `dirtyRoomCount reflects pending dirty rooms accurately`() {
        val bundle =
            buildHelper(
                checkpointStore = SnapshotTestCheckpointStore(initial = mapOf("chat_logs" to 10L)),
                chatLogRepository = SnapshotTestChatLogRepository(latestLogId = 10L),
                snapshotReader =
                    SnapshotTestSnapshotReader(
                        rooms = listOf(100L, 200L),
                        snapshots =
                            mapOf(
                                100L to listOf(snapshot(chatId = 100L, members = setOf(1L)), snapshot(chatId = 100L, members = setOf(1L, 10L))),
                                200L to listOf(snapshot(chatId = 200L, members = setOf(2L)), snapshot(chatId = 200L, members = setOf(2L, 20L))),
                            ),
                    ),
                diffEngine = SnapshotTestCountingDiffEngine(),
            )

        bundle.helper.seedSnapshotCache()
        waitUntil { bundle.snapshotReader.snapshotCalls.size == 2 }

        bundle.helper.markRoomDirty(100L)
        bundle.helper.markRoomDirty(200L)
        waitUntil { bundle.helper.dirtyRoomCount() == 2 }
        assertEquals(2, bundle.helper.dirtyRoomCount())

        bundle.helper.runDirtySnapshotDiff(maxRoomsPerTick = 1)
        waitUntil { bundle.helper.dirtyRoomCount() == 1 }
        assertEquals(1, bundle.helper.dirtyRoomCount())
    }

    private fun buildHelper(
        checkpointStore: SnapshotTestCheckpointStore,
        chatLogRepository: SnapshotTestChatLogRepository,
        snapshotReader: SnapshotTestSnapshotReader,
        diffEngine: RoomDiffEngine,
    ): TestObserverHelperBundle {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val bus = SseEventBus(bufferSize = 16)
        val routingGateway = SnapshotTestRecordingRoutingGateway()
        val checkpointJournal =
            BatchedCheckpointJournal(
                store = checkpointStore,
                flushIntervalMs = Long.MAX_VALUE,
                clock = { 0L },
            )
        val emitter = SnapshotEventEmitter(bus = bus, routingGateway = routingGateway)
        val coordinator = SnapshotCoordinator(scope, snapshotReader, diffEngine, emitter)
        val ingressService =
            CommandIngressService(
                db = chatLogRepository,
                config = config,
                checkpointJournal = checkpointJournal,
                routingGateway = routingGateway,
                onMarkDirty = { chatId ->
                    snapshotCoordinatorMarkDirty(coordinator, chatId)
                },
            )
        val helper = ObserverHelper(ingressService, coordinator, checkpointJournal)
        return TestObserverHelperBundle(
            helper = helper,
            coordinator = coordinator,
            bus = bus,
            routingGateway = routingGateway,
            diffEngine = diffEngine as? SnapshotTestCountingDiffEngine,
            snapshotReader = snapshotReader,
        )
    }

    private fun snapshot(
        chatId: Long,
        members: Set<Long>,
        nicknames: Map<Long, String> = emptyMap(),
        profileImages: Map<Long, String> = emptyMap(),
    ): RoomSnapshotData =
        RoomSnapshotData(
            chatId = chatId,
            linkId = chatId + 1000L,
            memberIds = members,
            blindedIds = emptySet(),
            nicknames = nicknames,
            roles = members.associateWith { 2 },
            profileImages = profileImages,
        )

    private fun waitUntil(
        timeoutMs: Long = 1_000L,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L)
        }
        assertTrue(condition(), "condition not met within ${timeoutMs}ms")
    }
}

private fun snapshotCoordinatorMarkDirty(
    coordinator: SnapshotCoordinator,
    chatId: Long,
) {
    kotlinx.coroutines.runBlocking {
        coordinator.send(
            party.qwer.iris.snapshot.SnapshotCommand
                .MarkDirty(chatId),
        )
    }
}

data class TestObserverHelperBundle(
    val helper: ObserverHelper,
    val coordinator: SnapshotCoordinator,
    val bus: SseEventBus,
    val routingGateway: SnapshotTestRecordingRoutingGateway,
    val diffEngine: SnapshotTestCountingDiffEngine?,
    val snapshotReader: SnapshotTestSnapshotReader,
)

class SnapshotTestCountingDiffEngine : RoomDiffEngine {
    @Volatile
    var diffCalls = 0
    val diffedChatIds = CopyOnWriteArrayList<Long>()

    override fun diff(
        prev: RoomSnapshotData,
        curr: RoomSnapshotData,
    ): List<Any> {
        diffCalls++
        diffedChatIds += curr.chatId
        val joined = (curr.memberIds - prev.memberIds).firstOrNull() ?: return emptyList()
        return listOf(
            MemberEvent(
                event = "join",
                chatId = curr.chatId,
                linkId = curr.linkId,
                userId = joined,
                nickname = curr.nicknames[joined],
                estimated = false,
                timestamp = 1L,
            ),
        )
    }
}

class SnapshotTestRecordingRoutingGateway : RoutingGateway {
    val commands = CopyOnWriteArrayList<RoutingCommand>()

    override fun route(command: RoutingCommand): RoutingResult {
        commands += command
        return RoutingResult.ACCEPTED
    }

    override fun close() {}
}

class SnapshotTestCheckpointStore(
    initial: Map<String, Long> = emptyMap(),
) : CheckpointStore {
    private val saved = initial.toMutableMap()

    override fun load(streamName: String): Long? = saved[streamName]

    override fun save(
        streamName: String,
        lastLogId: Long,
    ) {
        saved[streamName] = lastLogId
    }
}

class SnapshotTestChatLogRepository(
    var latestLogId: Long = 1L,
    var polledLogs: List<KakaoDB.ChatLogEntry> = emptyList(),
) : ChatLogRepository {
    override fun pollChatLogsAfter(
        afterLogId: Long,
        limit: Int,
    ): List<KakaoDB.ChatLogEntry> = polledLogs

    override fun resolveSenderName(userId: Long): String = userId.toString()

    override fun resolveRoomMetadata(chatId: Long): KakaoDB.RoomMetadata = KakaoDB.RoomMetadata()

    override fun latestLogId(): Long = latestLogId

    override fun executeQuery(
        sqlQuery: String,
        bindArgs: Array<String?>?,
        maxRows: Int,
    ): QueryExecutionResult = QueryExecutionResult(columns = emptyList(), rows = emptyList())
}

class SnapshotTestSnapshotReader(
    private val rooms: List<Long>,
    private val snapshots: Map<Long, List<RoomSnapshotData>>,
) : RoomSnapshotReader {
    val snapshotCalls = CopyOnWriteArrayList<Long>()
    private val snapshotIndexes = mutableMapOf<Long, Int>()

    override fun listRoomChatIds(): List<Long> = rooms

    override fun snapshot(chatId: Long): RoomSnapshotData {
        snapshotCalls += chatId
        val roomSnapshots = snapshots.getValue(chatId)
        val currentIndex = snapshotIndexes[chatId] ?: 0
        val safeIndex = minOf(currentIndex, roomSnapshots.lastIndex)
        snapshotIndexes[chatId] = safeIndex + 1
        return roomSnapshots[safeIndex]
    }
}
