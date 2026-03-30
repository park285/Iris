package party.qwer.iris

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import party.qwer.iris.delivery.webhook.RoutingCommand
import party.qwer.iris.delivery.webhook.RoutingGateway
import party.qwer.iris.delivery.webhook.RoutingResult
import party.qwer.iris.model.MemberEvent
import party.qwer.iris.persistence.BatchedCheckpointJournal
import party.qwer.iris.snapshot.RoomDiffEngine
import party.qwer.iris.snapshot.RoomSnapshotReader
import party.qwer.iris.snapshot.SnapshotCoordinator
import party.qwer.iris.snapshot.SnapshotEventEmitter
import party.qwer.iris.storage.ChatId
import party.qwer.iris.storage.LinkId
import party.qwer.iris.storage.UserId
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SnapshotObserverTest {
    @Test
    fun `full reconcile triggers after configured interval`() {
        val currentTimeMs = AtomicLong(0L)
        val diffEngine = SnapshotObserverTestDiffEngine()
        val rooms = mutableListOf(100L)
        val snapshotReader =
            SnapshotObserverTestSnapshotReader(
                roomsProvider = { rooms.toList() },
                snapshots =
                    mapOf(
                        100L to
                            listOf(
                                snapshotObserverTestSnapshot(chatId = 100L, members = setOf(1L), nicknames = mapOf(1L to "Alice")),
                                snapshotObserverTestSnapshot(chatId = 100L, members = setOf(1L), nicknames = mapOf(1L to "Alice Updated")),
                            ),
                        200L to
                            listOf(
                                snapshotObserverTestSnapshot(chatId = 200L, members = setOf(2L), nicknames = mapOf(2L to "Bob")),
                            ),
                    ),
            )
        val checkpointStore = SnapshotObserverTestCheckpointStore(initial = mapOf("chat_logs" to 10L))
        val checkpointJournal =
            BatchedCheckpointJournal(
                store = checkpointStore,
                flushIntervalMs = 0L,
                clock = currentTimeMs::get,
            )
        val bus = SseEventBus(bufferSize = 16)
        val routingGateway = SnapshotObserverTestRoutingGateway()
        val coordinator =
            SnapshotCoordinator(
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                roomSnapshotReader = snapshotReader,
                diffEngine = diffEngine,
                emitter = SnapshotEventEmitter(bus, routingGateway),
            )
        val observer =
            SnapshotObserver(
                snapshotCoordinator = coordinator,
                checkpointJournal = checkpointJournal,
                intervalMs = 50L,
                maxRoomsPerTick = 32,
                fullReconcileIntervalMs = 200L,
                clock = currentTimeMs::get,
            )

        runBlockingSeed(coordinator)
        observer.start()
        rooms += 200L
        currentTimeMs.set(250L)
        waitUntil(timeoutMs = 1_000L) { snapshotReader.snapshotCalls.contains(200L) }
        observer.stop()

        assertTrue(diffEngine.diffCalls >= 1)
        assertTrue(diffEngine.diffedChatIds.contains(100L))
        assertTrue(snapshotReader.snapshotCalls.contains(200L))
    }

    @Test
    fun `adaptive drain increases budget under backlog pressure`() {
        val rooms =
            (1L..256L).associateWith { chatId ->
                listOf(
                    snapshotObserverTestSnapshot(chatId = chatId, members = setOf(chatId)),
                    snapshotObserverTestSnapshot(chatId = chatId, members = setOf(chatId, chatId + 10_000L)),
                )
            }
        val diffEngine = SnapshotObserverTestDiffEngine()
        val snapshotReader =
            SnapshotObserverTestSnapshotReader(
                rooms = rooms.keys.toList(),
                snapshots = rooms,
            )
        val checkpointJournal =
            BatchedCheckpointJournal(
                store = SnapshotObserverTestCheckpointStore(initial = mapOf("chat_logs" to 10L)),
                flushIntervalMs = Long.MAX_VALUE,
                clock = { 0L },
            )
        val coordinator =
            SnapshotCoordinator(
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                roomSnapshotReader = snapshotReader,
                diffEngine = diffEngine,
                emitter = SnapshotEventEmitter(SseEventBus(bufferSize = 512), SnapshotObserverTestRoutingGateway()),
            )
        val observer =
            SnapshotObserver(
                snapshotCoordinator = coordinator,
                checkpointJournal = checkpointJournal,
                intervalMs = 5_000L,
                maxRoomsPerTick = 32,
                fullReconcileIntervalMs = 60_000L,
            )

        runBlockingSeed(coordinator)
        runBlockingFullReconcile(coordinator)
        waitUntil(timeoutMs = 1_000L) { coordinator.dirtyRoomCount() == 256 }

        observer.start()
        waitUntil(timeoutMs = 1_000L) { diffEngine.diffCalls >= 128 }
        observer.stop()

        assertEquals(128, diffEngine.diffCalls)
        assertEquals(128, coordinator.dirtyRoomCount())
    }

    private fun waitUntil(
        timeoutMs: Long,
        condition: () -> Boolean,
    ) = kotlinx.coroutines.runBlocking {
        kotlinx.coroutines.withTimeout(timeoutMs) {
            while (!condition()) {
                kotlinx.coroutines.delay(10L)
            }
        }
    }
}

private fun runBlockingSeed(coordinator: SnapshotCoordinator) {
    kotlinx.coroutines.runBlocking {
        coordinator.send(party.qwer.iris.snapshot.SnapshotCommand.SeedCache)
    }
}

private fun runBlockingFullReconcile(coordinator: SnapshotCoordinator) {
    kotlinx.coroutines.runBlocking {
        coordinator.send(party.qwer.iris.snapshot.SnapshotCommand.FullReconcile)
    }
}

private fun snapshotObserverTestSnapshot(
    chatId: Long,
    members: Set<Long>,
    nicknames: Map<Long, String> = emptyMap(),
): RoomSnapshotData =
    RoomSnapshotData(
        chatId = ChatId(chatId),
        linkId = LinkId(chatId + 1000L),
        memberIds = members.map(::UserId).toSet(),
        blindedIds = emptySet(),
        nicknames = nicknames.map { (k, v) -> UserId(k) to v }.toMap(),
        roles = members.associate { UserId(it) to 2 },
        profileImages = emptyMap(),
    )

private class SnapshotObserverTestDiffEngine : RoomDiffEngine {
    private val diffCallCounter = AtomicInteger(0)
    val diffCalls: Int
        get() = diffCallCounter.get()
    val diffedChatIds = CopyOnWriteArrayList<Long>()

    override fun diff(
        prev: RoomSnapshotData,
        curr: RoomSnapshotData,
    ): List<Any> {
        diffCallCounter.incrementAndGet()
        diffedChatIds += curr.chatId.value
        val joined = (curr.memberIds - prev.memberIds).firstOrNull() ?: return emptyList()
        return listOf(
            MemberEvent(
                event = "join",
                chatId = curr.chatId.value,
                linkId = curr.linkId?.value,
                userId = joined.value,
                nickname = curr.nicknames[joined],
                estimated = false,
                timestamp = 1L,
            ),
        )
    }
}

private class SnapshotObserverTestRoutingGateway : RoutingGateway {
    override fun route(command: RoutingCommand): RoutingResult = RoutingResult.ACCEPTED

    override fun close() {}
}

private class SnapshotObserverTestCheckpointStore(
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

private class SnapshotObserverTestSnapshotReader(
    private val roomsProvider: () -> List<Long>,
    private val snapshots: Map<Long, List<RoomSnapshotData>>,
) : RoomSnapshotReader {
    constructor(
        rooms: List<Long>,
        snapshots: Map<Long, List<RoomSnapshotData>>,
    ) : this({ rooms }, snapshots)

    private val snapshotIndexes = mutableMapOf<Long, Int>()
    val snapshotCalls = CopyOnWriteArrayList<Long>()

    override fun listRoomChatIds(): List<ChatId> = roomsProvider().map(::ChatId)

    override fun snapshot(chatId: ChatId): RoomSnapshotData {
        snapshotCalls += chatId.value
        val roomSnapshots = snapshots.getValue(chatId.value)
        val currentIndex = snapshotIndexes[chatId.value] ?: 0
        val safeIndex = minOf(currentIndex, roomSnapshots.lastIndex)
        snapshotIndexes[chatId.value] = safeIndex + 1
        return roomSnapshots[safeIndex]
    }
}
