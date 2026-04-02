package party.qwer.iris

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import party.qwer.iris.delivery.webhook.RoutingCommand
import party.qwer.iris.delivery.webhook.RoutingGateway
import party.qwer.iris.delivery.webhook.RoutingResult
import party.qwer.iris.model.MemberEvent
import party.qwer.iris.model.RoomEvent
import party.qwer.iris.model.RoomEventRecord
import party.qwer.iris.persistence.BatchedCheckpointJournal
import party.qwer.iris.persistence.RoomEventStore
import party.qwer.iris.snapshot.RoomDiffEngine
import party.qwer.iris.snapshot.RoomSnapshotReadResult
import party.qwer.iris.snapshot.RoomSnapshotReader
import party.qwer.iris.snapshot.SnapshotCoordinator
import party.qwer.iris.snapshot.SnapshotEventEmitter
import party.qwer.iris.storage.ChatId
import party.qwer.iris.storage.LinkId
import party.qwer.iris.storage.UserId
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SnapshotObserverTest {
    @Test
    fun `full reconcile triggers after configured interval`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
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
                    clock = { testScheduler.currentTime },
                )
            val bus = SseEventBus(bufferSize = 16)
            val routingGateway = SnapshotObserverTestRoutingGateway()
            val coordinatorScope = CoroutineScope(SupervisorJob() + dispatcher)
            val coordinator =
                SnapshotCoordinator(
                    scope = coordinatorScope,
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
                    clock = { testScheduler.currentTime },
                    dispatcher = dispatcher,
                )

            coordinator.send(party.qwer.iris.snapshot.SnapshotCommand.SeedCache)
            runCurrent()
            try {
                observer.start()
                rooms += 200L
                advanceTimeBy(250L)
                runCurrent()
                observer.stopSuspend()

                assertTrue(diffEngine.diffCalls >= 1)
                assertTrue(diffEngine.diffedChatIds.contains(100L))
                assertTrue(snapshotReader.snapshotCalls.contains(200L))
            } finally {
                bus.close()
                coordinatorScope.cancel()
            }
        }

    @Test
    fun `event store prune runs after configured interval`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val diffEngine = SnapshotObserverTestDiffEngine()
            val snapshotReader =
                SnapshotObserverTestSnapshotReader(
                    rooms = listOf(100L),
                    snapshots =
                        mapOf(
                            100L to
                                listOf(
                                    snapshotObserverTestSnapshot(chatId = 100L, members = setOf(1L)),
                                ),
                        ),
                )
            val checkpointJournal =
                BatchedCheckpointJournal(
                    store = SnapshotObserverTestCheckpointStore(),
                    flushIntervalMs = Long.MAX_VALUE,
                    clock = { testScheduler.currentTime },
                )
            val bus = SseEventBus(bufferSize = 16)
            val coordinatorScope = CoroutineScope(SupervisorJob() + dispatcher)
            val coordinator =
                SnapshotCoordinator(
                    scope = coordinatorScope,
                    roomSnapshotReader = snapshotReader,
                    diffEngine = diffEngine,
                    emitter = SnapshotEventEmitter(bus, SnapshotObserverTestRoutingGateway()),
                )
            val eventStore = RecordingRoomEventStore()
            val observer =
                SnapshotObserver(
                    snapshotCoordinator = coordinator,
                    checkpointJournal = checkpointJournal,
                    intervalMs = 50L,
                    fullReconcileIntervalMs = Long.MAX_VALUE,
                    eventRetentionMs = 100L,
                    eventPruneIntervalMs = 200L,
                    roomEventStore = eventStore,
                    clock = { testScheduler.currentTime },
                    dispatcher = dispatcher,
                )

            coordinator.send(party.qwer.iris.snapshot.SnapshotCommand.SeedCache)
            runCurrent()
            try {
                observer.start()
                // 200ms 후 첫 prune 발생해야 함
                advanceTimeBy(250L)
                runCurrent()
                observer.stopSuspend()

                assertTrue(eventStore.pruneCalls.isNotEmpty(), "pruneOlderThan이 호출되어야 함")
                // cutoff = currentTime - retentionMs
                val lastCutoff = eventStore.pruneCalls.last()
                assertTrue(lastCutoff > 0, "cutoff는 양수여야 함")
            } finally {
                bus.close()
                coordinatorScope.cancel()
            }
        }

    @Test
    fun `event store prune does not run when retention is null`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val diffEngine = SnapshotObserverTestDiffEngine()
            val snapshotReader =
                SnapshotObserverTestSnapshotReader(
                    rooms = listOf(100L),
                    snapshots =
                        mapOf(
                            100L to
                                listOf(
                                    snapshotObserverTestSnapshot(chatId = 100L, members = setOf(1L)),
                                ),
                        ),
                )
            val checkpointJournal =
                BatchedCheckpointJournal(
                    store = SnapshotObserverTestCheckpointStore(),
                    flushIntervalMs = Long.MAX_VALUE,
                    clock = { testScheduler.currentTime },
                )
            val bus = SseEventBus(bufferSize = 16)
            val coordinatorScope = CoroutineScope(SupervisorJob() + dispatcher)
            val coordinator =
                SnapshotCoordinator(
                    scope = coordinatorScope,
                    roomSnapshotReader = snapshotReader,
                    diffEngine = diffEngine,
                    emitter = SnapshotEventEmitter(bus, SnapshotObserverTestRoutingGateway()),
                )
            val eventStore = RecordingRoomEventStore()
            val observer =
                SnapshotObserver(
                    snapshotCoordinator = coordinator,
                    checkpointJournal = checkpointJournal,
                    intervalMs = 50L,
                    fullReconcileIntervalMs = Long.MAX_VALUE,
                    eventRetentionMs = null,
                    roomEventStore = eventStore,
                    clock = { testScheduler.currentTime },
                    dispatcher = dispatcher,
                )

            coordinator.send(party.qwer.iris.snapshot.SnapshotCommand.SeedCache)
            runCurrent()
            try {
                observer.start()
                advanceTimeBy(250L)
                runCurrent()
                observer.stopSuspend()

                assertTrue(eventStore.pruneCalls.isEmpty(), "retention=null일 때 prune 호출되면 안됨")
            } finally {
                bus.close()
                coordinatorScope.cancel()
            }
        }

    @Test
    fun `adaptive drain increases budget under backlog pressure`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
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
                    clock = { testScheduler.currentTime },
                )
            val bus = SseEventBus(bufferSize = 512)
            val coordinatorScope = CoroutineScope(SupervisorJob() + dispatcher)
            val coordinator =
                SnapshotCoordinator(
                    scope = coordinatorScope,
                    roomSnapshotReader = snapshotReader,
                    diffEngine = diffEngine,
                    emitter = SnapshotEventEmitter(bus, SnapshotObserverTestRoutingGateway()),
                )
            val observer =
                SnapshotObserver(
                    snapshotCoordinator = coordinator,
                    checkpointJournal = checkpointJournal,
                    intervalMs = 5_000L,
                    maxRoomsPerTick = 32,
                    fullReconcileIntervalMs = 60_000L,
                    clock = { testScheduler.currentTime },
                    dispatcher = dispatcher,
                )

            coordinator.send(party.qwer.iris.snapshot.SnapshotCommand.SeedCache)
            coordinator.send(party.qwer.iris.snapshot.SnapshotCommand.FullReconcile)
            runCurrent()
            assertEquals(256, coordinator.dirtyRoomCount())

            try {
                observer.start()
                runCurrent()
                observer.stopSuspend()

                assertEquals(128, diffEngine.diffCalls)
                assertEquals(128, coordinator.dirtyRoomCount())
            } finally {
                bus.close()
                coordinatorScope.cancel()
            }
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
    ): List<RoomEvent> {
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

    override fun diffMissing(prev: RoomSnapshotData): List<RoomEvent> = emptyList()

    override fun diffRestored(curr: RoomSnapshotData): List<RoomEvent> = emptyList()
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

private class RecordingRoomEventStore : RoomEventStore {
    val pruneCalls = CopyOnWriteArrayList<Long>()

    override fun insert(
        chatId: Long,
        eventType: String,
        userId: Long,
        payload: String,
        createdAtMs: Long,
    ): Long = 0L

    override fun listByChatId(
        chatId: Long,
        limit: Int,
        afterId: Long,
    ): List<RoomEventRecord> = emptyList()

    override fun maxId(): Long = 0L

    override fun pruneOlderThan(cutoffMs: Long): Int {
        pruneCalls += cutoffMs
        return 0
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

    override fun snapshot(chatId: ChatId): RoomSnapshotReadResult {
        snapshotCalls += chatId.value
        val roomSnapshots = snapshots.getValue(chatId.value)
        val currentIndex = snapshotIndexes[chatId.value] ?: 0
        val safeIndex = minOf(currentIndex, roomSnapshots.lastIndex)
        snapshotIndexes[chatId.value] = safeIndex + 1
        return RoomSnapshotReadResult.Present(roomSnapshots[safeIndex])
    }
}
