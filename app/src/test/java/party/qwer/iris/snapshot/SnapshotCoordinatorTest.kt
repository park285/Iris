package party.qwer.iris.snapshot

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import party.qwer.iris.RoomSnapshotData
import party.qwer.iris.RoomSnapshotManager
import party.qwer.iris.SseEventBus
import party.qwer.iris.model.MemberEvent
import party.qwer.iris.model.RoomEvent
import party.qwer.iris.persistence.InMemorySnapshotStateStore
import party.qwer.iris.storage.ChatId
import party.qwer.iris.storage.LinkId
import party.qwer.iris.storage.UserId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SnapshotCoordinatorTest {
    private fun snapshot(
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

    @Test
    fun `seedCache populates previousSnapshots for all rooms`() =
        runBlocking {
            val reader =
                StubSnapshotReader(
                    rooms = listOf(100L, 200L),
                    snapshots =
                        mapOf(
                            100L to listOf(snapshot(100L, setOf(1L))),
                            200L to listOf(snapshot(200L, setOf(2L))),
                        ),
                )
            val diffEngine = RecordingDiffEngine()
            val emitter = RecordingEmitter()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val coordinator = SnapshotCoordinator(scope, reader, diffEngine, emitter)

            coordinator.send(SnapshotCommand.SeedCache)
            yield()

            coordinator.send(SnapshotCommand.Drain(budget = 10))
            yield()

            assertEquals(0, diffEngine.calls)
            scope.cancel()
        }

    @Test
    fun `markDirty followed by drain triggers diff and emits events`() =
        runBlocking {
            val snap1 = snapshot(100L, setOf(1L), nicknames = mapOf(1L to "Alice"))
            val snap2 = snapshot(100L, setOf(1L, 2L), nicknames = mapOf(1L to "Alice", 2L to "Bob"))
            val reader =
                StubSnapshotReader(
                    rooms = listOf(100L),
                    snapshots = mapOf(100L to listOf(snap1, snap2)),
                )
            val diffEngine = RecordingDiffEngine()
            val emitter = RecordingEmitter()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val coordinator = SnapshotCoordinator(scope, reader, diffEngine, emitter)

            coordinator.send(SnapshotCommand.SeedCache)
            yield()
            coordinator.send(SnapshotCommand.MarkDirty(chatId = ChatId(100L)))
            yield()
            coordinator.send(SnapshotCommand.Drain(budget = 10))
            yield()

            assertEquals(1, diffEngine.calls)
            assertEquals(listOf(100L), diffEngine.diffedChatIds)
            assertEquals(1, emitter.emitCalls)
            scope.cancel()
        }

    @Test
    fun `duplicate markDirty does not queue twice`() =
        runBlocking {
            val snap1 = snapshot(100L, setOf(1L))
            val snap2 = snapshot(100L, setOf(1L, 2L))
            val reader =
                StubSnapshotReader(
                    rooms = listOf(100L),
                    snapshots = mapOf(100L to listOf(snap1, snap2)),
                )
            val diffEngine = RecordingDiffEngine()
            val emitter = RecordingEmitter()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val coordinator = SnapshotCoordinator(scope, reader, diffEngine, emitter)

            coordinator.send(SnapshotCommand.SeedCache)
            yield()
            coordinator.send(SnapshotCommand.MarkDirty(chatId = ChatId(100L)))
            coordinator.send(SnapshotCommand.MarkDirty(chatId = ChatId(100L)))
            coordinator.send(SnapshotCommand.MarkDirty(chatId = ChatId(100L)))
            yield()
            coordinator.send(SnapshotCommand.Drain(budget = 10))
            yield()

            assertEquals(1, diffEngine.calls)
            scope.cancel()
        }

    @Test
    fun `drain respects budget limit`() =
        runBlocking {
            val reader =
                StubSnapshotReader(
                    rooms = listOf(100L, 200L, 300L),
                    snapshots =
                        mapOf(
                            100L to listOf(snapshot(100L, setOf(1L)), snapshot(100L, setOf(1L, 10L))),
                            200L to listOf(snapshot(200L, setOf(2L)), snapshot(200L, setOf(2L, 20L))),
                            300L to listOf(snapshot(300L, setOf(3L)), snapshot(300L, setOf(3L, 30L))),
                        ),
                )
            val diffEngine = RecordingDiffEngine()
            val emitter = RecordingEmitter()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val coordinator = SnapshotCoordinator(scope, reader, diffEngine, emitter)

            coordinator.send(SnapshotCommand.SeedCache)
            yield()
            coordinator.send(SnapshotCommand.MarkDirty(chatId = ChatId(100L)))
            coordinator.send(SnapshotCommand.MarkDirty(chatId = ChatId(200L)))
            coordinator.send(SnapshotCommand.MarkDirty(chatId = ChatId(300L)))
            yield()

            coordinator.send(SnapshotCommand.Drain(budget = 1))
            yield()

            assertEquals(1, diffEngine.calls)

            coordinator.send(SnapshotCommand.Drain(budget = 10))
            yield()

            assertEquals(3, diffEngine.calls)
            scope.cancel()
        }

    @Test
    fun `fullReconcile marks all seeded rooms dirty`() =
        runBlocking {
            val reader =
                StubSnapshotReader(
                    rooms = listOf(100L, 200L),
                    snapshots =
                        mapOf(
                            100L to listOf(snapshot(100L, setOf(1L)), snapshot(100L, setOf(1L, 10L))),
                            200L to listOf(snapshot(200L, setOf(2L)), snapshot(200L, setOf(2L, 20L))),
                        ),
                )
            val diffEngine = RecordingDiffEngine()
            val emitter = RecordingEmitter()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val coordinator = SnapshotCoordinator(scope, reader, diffEngine, emitter)

            coordinator.send(SnapshotCommand.SeedCache)
            yield()
            coordinator.send(SnapshotCommand.FullReconcile)
            yield()
            coordinator.send(SnapshotCommand.Drain(budget = 10))
            yield()

            assertEquals(2, diffEngine.calls)
            scope.cancel()
        }

    @Test
    fun `fullReconcile rereads current room ids and includes newly visible rooms`() =
        runBlocking {
            val rooms = mutableListOf(100L)
            val reader =
                StubSnapshotReader(
                    roomsProvider = { rooms.map(::ChatId) },
                    snapshots =
                        mapOf(
                            100L to listOf(snapshot(100L, setOf(1L)), snapshot(100L, setOf(1L, 10L))),
                            200L to listOf(snapshot(200L, setOf(2L)), snapshot(200L, setOf(2L))),
                        ),
                )
            val diffEngine = RecordingDiffEngine()
            val emitter = RecordingEmitter()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val coordinator = SnapshotCoordinator(scope, reader, diffEngine, emitter)

            coordinator.send(SnapshotCommand.SeedCache)
            yield()

            rooms += 200L

            coordinator.send(SnapshotCommand.FullReconcile)
            yield()
            assertEquals(2, coordinator.dirtyRoomCount())

            coordinator.send(SnapshotCommand.Drain(budget = 10))
            yield()

            assertEquals(listOf(100L), diffEngine.diffedChatIds)
            assertEquals(0, coordinator.dirtyRoomCount())
            assertEquals(listOf(100L, 100L, 200L), reader.snapshotCalls)
            scope.cancel()
        }

    @Test
    fun `fullReconcile also drains rooms removed from current room source`() =
        runBlocking {
            val rooms = mutableListOf(100L, 200L)
            val reader =
                StubSnapshotReader(
                    roomsProvider = { rooms.map(::ChatId) },
                    snapshots =
                        mapOf(
                            100L to listOf(snapshot(100L, setOf(1L)), snapshot(100L, emptySet())),
                            200L to listOf(snapshot(200L, setOf(2L)), snapshot(200L, setOf(2L, 20L))),
                        ),
                )
            val diffEngine = RecordingDiffEngine()
            val emitter = RecordingEmitter()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val coordinator = SnapshotCoordinator(scope, reader, diffEngine, emitter)

            coordinator.send(SnapshotCommand.SeedCache)
            yield()

            rooms.remove(100L)

            coordinator.send(SnapshotCommand.FullReconcile)
            yield()
            coordinator.send(SnapshotCommand.Drain(budget = 10))
            yield()

            assertEquals(listOf(200L), diffEngine.diffedChatIds)
            assertEquals(listOf(100L), diffEngine.missingChatIds)
            assertEquals(listOf(100L, 200L, 100L, 200L), reader.snapshotCalls)
            scope.cancel()
        }

    @Test
    fun `deleted room is diffed once and then dropped from future full reconcile passes`() =
        runBlocking {
            val rooms = mutableListOf(100L, 200L)
            val reader =
                StubSnapshotReader(
                    roomsProvider = { rooms.map(::ChatId) },
                    snapshots =
                        mapOf(
                            100L to listOf(snapshot(100L, setOf(1L), nicknames = mapOf(1L to "Alice"))),
                            200L to listOf(snapshot(200L, setOf(2L)), snapshot(200L, setOf(2L))),
                        ),
                )
            val emitter = RecordingEmitter()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val coordinator = SnapshotCoordinator(scope, reader, RoomSnapshotManager(), emitter)

            coordinator.send(SnapshotCommand.SeedCache)
            yield()

            rooms.remove(100L)

            coordinator.send(SnapshotCommand.FullReconcile)
            yield()
            coordinator.send(SnapshotCommand.Drain(budget = 10))
            yield()

            val firstPassEvents = emitter.emittedEvents.toList()
            emitter.emittedEvents.clear()

            coordinator.send(SnapshotCommand.FullReconcile)
            yield()
            coordinator.send(SnapshotCommand.Drain(budget = 10))
            yield()

            assertEquals(listOf("leave"), firstPassEvents.filterIsInstance<MemberEvent>().map { it.event })
            assertEquals(listOf(100L), firstPassEvents.filterIsInstance<MemberEvent>().map { it.chatId })
            assertEquals(emptyList(), emitter.emittedEvents)
            assertEquals(2, reader.snapshotCalls.count { it == 100L })
            scope.cancel()
        }

    @Test
    fun `dirtyRoomCount reflects pending rooms`() =
        runBlocking {
            val reader =
                StubSnapshotReader(
                    rooms = listOf(100L, 200L),
                    snapshots =
                        mapOf(
                            100L to listOf(snapshot(100L, setOf(1L)), snapshot(100L, setOf(1L, 10L))),
                            200L to listOf(snapshot(200L, setOf(2L)), snapshot(200L, setOf(2L, 20L))),
                        ),
                )
            val diffEngine = RecordingDiffEngine()
            val emitter = RecordingEmitter()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val coordinator = SnapshotCoordinator(scope, reader, diffEngine, emitter)

            coordinator.send(SnapshotCommand.SeedCache)
            yield()
            coordinator.send(SnapshotCommand.MarkDirty(chatId = ChatId(100L)))
            coordinator.send(SnapshotCommand.MarkDirty(chatId = ChatId(200L)))
            yield()

            assertEquals(2, coordinator.dirtyRoomCount())

            coordinator.send(SnapshotCommand.Drain(budget = 1))
            yield()

            assertEquals(1, coordinator.dirtyRoomCount())
            scope.cancel()
        }

    @Test
    fun `drain on unseeded room skips diff`() =
        runBlocking {
            val reader =
                StubSnapshotReader(
                    rooms = emptyList(),
                    snapshots = mapOf(100L to listOf(snapshot(100L, setOf(1L)))),
                )
            val diffEngine = RecordingDiffEngine()
            val emitter = RecordingEmitter()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val coordinator = SnapshotCoordinator(scope, reader, diffEngine, emitter)

            coordinator.send(SnapshotCommand.MarkDirty(chatId = ChatId(100L)))
            yield()
            coordinator.send(SnapshotCommand.Drain(budget = 10))
            yield()

            assertEquals(0, diffEngine.calls)
            scope.cancel()
        }

    @Test
    fun `drain emits leave events when room becomes missing`() =
        runBlocking {
            val rooms = mutableListOf(100L, 200L)
            val reader =
                StubSnapshotReader(
                    roomsProvider = { rooms.map(::ChatId) },
                    snapshots =
                        mapOf(
                            100L to listOf(snapshot(100L, setOf(1L), nicknames = mapOf(1L to "Alice"))),
                            200L to listOf(snapshot(200L, setOf(2L))),
                        ),
                )
            val emitter = RecordingEmitter()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val coordinator = SnapshotCoordinator(scope, reader, RoomSnapshotManager(), emitter)

            coordinator.send(SnapshotCommand.SeedCache)
            yield()

            rooms.remove(100L)
            coordinator.send(SnapshotCommand.FullReconcile)
            yield()
            coordinator.send(SnapshotCommand.Drain(budget = 10))
            yield()

            val events = emitter.emittedEvents.filterIsInstance<MemberEvent>()
            assertEquals(listOf("leave"), events.map { it.event })
            assertEquals(listOf(100L), events.map { it.chatId })
            assertEquals(listOf(1L), events.map { it.userId })
            scope.cancel()
        }

    @Test
    fun `missing and restored rooms use explicit diff engine semantics`() =
        runBlocking {
            val rooms = mutableListOf(100L, 200L)
            val reader =
                StubSnapshotReader(
                    roomsProvider = { rooms.map(::ChatId) },
                    snapshots =
                        mapOf(
                            100L to
                                listOf(
                                    snapshot(100L, setOf(1L), nicknames = mapOf(1L to "Alice")),
                                    snapshot(100L, setOf(2L), nicknames = mapOf(2L to "Bob")),
                                ),
                            200L to listOf(snapshot(200L, setOf(9L)), snapshot(200L, setOf(9L))),
                        ),
                )
            val diffEngine = RecordingDiffEngine()
            val emitter = RecordingEmitter()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val coordinator = SnapshotCoordinator(scope, reader, diffEngine, emitter)

            coordinator.send(SnapshotCommand.SeedCache)
            yield()

            rooms.remove(100L)
            coordinator.send(SnapshotCommand.FullReconcile)
            yield()
            coordinator.send(SnapshotCommand.Drain(budget = 10))
            yield()

            rooms += 100L
            coordinator.send(SnapshotCommand.FullReconcile)
            yield()
            coordinator.send(SnapshotCommand.Drain(budget = 10))
            yield()

            assertEquals(listOf(100L), diffEngine.missingChatIds)
            assertEquals(listOf(100L), diffEngine.restoredChatIds)
            assertEquals(listOf(200L, 200L), diffEngine.diffedChatIds)
            scope.cancel()
        }

    @Test
    fun `persisted present snapshot emits missing diff after restart when room disappears`() =
        runBlocking {
            val stateStore = InMemorySnapshotStateStore()

            val initialReader =
                StubSnapshotReader(
                    rooms = listOf(100L),
                    snapshots = mapOf(100L to listOf(snapshot(100L, setOf(1L), nicknames = mapOf(1L to "Alice")))),
                )
            val initialScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            SnapshotCoordinator(
                scope = initialScope,
                roomSnapshotReader = initialReader,
                diffEngine = RecordingDiffEngine(),
                emitter = RecordingEmitter(),
                stateStore = stateStore,
            ).also { coordinator ->
                coordinator.send(SnapshotCommand.SeedCache)
                yield()
            }
            initialScope.cancel()

            val restartedReader =
                StubSnapshotReader(
                    roomsProvider = { listOf(ChatId(200L)) },
                    snapshots =
                        mapOf(
                            200L to listOf(snapshot(200L, setOf(9L))),
                        ),
                )
            val diffEngine = RecordingDiffEngine()
            val emitter = RecordingEmitter()
            val restartedScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val restartedCoordinator =
                SnapshotCoordinator(
                    scope = restartedScope,
                    roomSnapshotReader = restartedReader,
                    diffEngine = diffEngine,
                    emitter = emitter,
                    stateStore = stateStore,
                )

            restartedCoordinator.send(SnapshotCommand.SeedCache)
            yield()
            restartedCoordinator.send(SnapshotCommand.Drain(budget = 10))
            yield()

            assertEquals(listOf(100L), diffEngine.missingChatIds)
            assertTrue(emitter.emittedEvents.filterIsInstance<MemberEvent>().any { it.event == "leave" && it.chatId == 100L })
            restartedScope.cancel()
        }

    @Test
    fun `persisted missing state emits restored diff after restart when room returns`() =
        runBlocking {
            val stateStore = InMemorySnapshotStateStore()
            val rooms = mutableListOf(100L)

            val initialReader =
                StubSnapshotReader(
                    roomsProvider = { rooms.map(::ChatId) },
                    snapshots =
                        mapOf(
                            100L to
                                listOf(
                                    snapshot(100L, setOf(1L), nicknames = mapOf(1L to "Alice")),
                                    snapshot(100L, setOf(2L), nicknames = mapOf(2L to "Bob")),
                                ),
                        ),
                )
            val initialScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val initialCoordinator =
                SnapshotCoordinator(
                    scope = initialScope,
                    roomSnapshotReader = initialReader,
                    diffEngine = RecordingDiffEngine(),
                    emitter = RecordingEmitter(),
                    stateStore = stateStore,
                )

            initialCoordinator.send(SnapshotCommand.SeedCache)
            yield()
            rooms.clear()
            initialCoordinator.send(SnapshotCommand.MarkDirty(ChatId(100L)))
            yield()
            initialCoordinator.send(SnapshotCommand.Drain(budget = 10))
            yield()
            initialScope.cancel()

            val restartedReader =
                StubSnapshotReader(
                    roomsProvider = { listOf(ChatId(100L)) },
                    snapshots =
                        mapOf(
                            100L to listOf(snapshot(100L, setOf(2L), nicknames = mapOf(2L to "Bob"))),
                        ),
                )
            val diffEngine = RecordingDiffEngine()
            val emitter = RecordingEmitter()
            val restartedScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val restartedCoordinator =
                SnapshotCoordinator(
                    scope = restartedScope,
                    roomSnapshotReader = restartedReader,
                    diffEngine = diffEngine,
                    emitter = emitter,
                    stateStore = stateStore,
                )

            restartedCoordinator.send(SnapshotCommand.SeedCache)
            yield()
            restartedCoordinator.send(SnapshotCommand.Drain(budget = 10))
            yield()

            assertEquals(listOf(100L), diffEngine.restoredChatIds)
            assertTrue(emitter.emittedEvents.filterIsInstance<MemberEvent>().any { it.event == "join" && it.chatId == 100L })
            restartedScope.cancel()
        }

    @Test
    fun `resurrected room emits join events on reappearance`() =
        runBlocking {
            val rooms = mutableListOf(100L, 200L)
            val reader =
                StubSnapshotReader(
                    roomsProvider = { rooms.map(::ChatId) },
                    snapshots =
                        mapOf(
                            100L to
                                listOf(
                                    // 시드 시점의 스냅샷
                                    snapshot(100L, setOf(1L), nicknames = mapOf(1L to "Alice")),
                                    // 부활 후 새 멤버 스냅샷
                                    snapshot(100L, setOf(5L, 6L), nicknames = mapOf(5L to "Eve", 6L to "Frank")),
                                ),
                            200L to
                                listOf(
                                    snapshot(200L, setOf(2L)),
                                    snapshot(200L, setOf(2L)),
                                    snapshot(200L, setOf(2L)),
                                ),
                        ),
                )
            val emitter = RecordingEmitter()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val coordinator = SnapshotCoordinator(scope, reader, RoomSnapshotManager(), emitter)

            // 1단계: 시드
            coordinator.send(SnapshotCommand.SeedCache)
            yield()

            // 2단계: 방 100 삭제 → FullReconcile + Drain → leave 이벤트 확인
            rooms.remove(100L)
            coordinator.send(SnapshotCommand.FullReconcile)
            yield()
            coordinator.send(SnapshotCommand.Drain(budget = 10))
            yield()

            val leaveEvents = emitter.emittedEvents.filterIsInstance<MemberEvent>()
            assertEquals(listOf("leave"), leaveEvents.map { it.event })
            assertEquals(listOf(100L), leaveEvents.map { it.chatId })
            emitter.emittedEvents.clear()

            // 3단계: 방 100 부활 (새 멤버) → FullReconcile + Drain → join 이벤트 확인
            rooms.add(100L)
            coordinator.send(SnapshotCommand.FullReconcile)
            yield()
            coordinator.send(SnapshotCommand.Drain(budget = 10))
            yield()

            val joinEvents = emitter.emittedEvents.filterIsInstance<MemberEvent>()
            assertEquals(2, joinEvents.size)
            assertEquals(listOf("join", "join"), joinEvents.map { it.event })
            assertEquals(setOf(5L, 6L), joinEvents.map { it.userId }.toSet())
            assertEquals(listOf(100L, 100L), joinEvents.map { it.chatId })
            scope.cancel()
        }

    @Test
    fun `fullReconcile skips when room source returns empty but snapshots exist`() =
        runBlocking {
            val rooms = mutableListOf(100L, 200L, 300L)
            val reader =
                StubSnapshotReader(
                    roomsProvider = { rooms.map(::ChatId) },
                    snapshots =
                        mapOf(
                            100L to listOf(snapshot(100L, setOf(1L))),
                            200L to listOf(snapshot(200L, setOf(2L))),
                            300L to listOf(snapshot(300L, setOf(3L))),
                        ),
                )
            val diffEngine = RecordingDiffEngine()
            val emitter = RecordingEmitter()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val coordinator = SnapshotCoordinator(scope, reader, diffEngine, emitter)

            // 시드: 3개 방
            coordinator.send(SnapshotCommand.SeedCache)
            yield()

            // DB 장애 시뮬레이션 — 빈 목록 반환
            rooms.clear()

            coordinator.send(SnapshotCommand.FullReconcile)
            yield()
            coordinator.send(SnapshotCommand.Drain(budget = 10))
            yield()

            // 빈 결과일 때 reconcile 스킵 → diff/emit 호출 없음
            assertEquals(0, diffEngine.calls)
            assertEquals(0, emitter.emitCalls)
            scope.cancel()
        }

    @Test
    fun `enqueue accepts non suspending commands and updates dirty room count`() =
        runBlocking {
            val reader =
                StubSnapshotReader(
                    rooms = listOf(100L),
                    snapshots = mapOf(100L to listOf(snapshot(100L, setOf(1L)), snapshot(100L, setOf(1L, 2L)))),
                )
            val diffEngine = RecordingDiffEngine()
            val emitter = RecordingEmitter()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val coordinator = SnapshotCoordinator(scope, reader, diffEngine, emitter)

            coordinator.enqueue(SnapshotCommand.SeedCache)
            yield()
            coordinator.enqueue(SnapshotCommand.MarkDirty(chatId = ChatId(100L)))
            yield()

            assertEquals(1, coordinator.dirtyRoomCount())

            coordinator.enqueue(SnapshotCommand.Drain(budget = 10))
            yield()

            assertEquals(0, coordinator.dirtyRoomCount())
            assertEquals(1, diffEngine.calls)
            scope.cancel()
        }

    @Test
    fun `enqueue coalesces burst dirty marks before actor turn`() =
        runTest {
            val reader =
                StubSnapshotReader(
                    rooms = listOf(100L),
                    snapshots = mapOf(100L to listOf(snapshot(100L, setOf(1L)), snapshot(100L, setOf(1L, 2L)))),
                )
            val diffEngine = RecordingDiffEngine()
            val emitter = RecordingEmitter()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            val coordinator = SnapshotCoordinator(scope, reader, diffEngine, emitter)

            coordinator.enqueue(SnapshotCommand.SeedCache)
            coordinator.enqueue(SnapshotCommand.MarkDirty(chatId = ChatId(100L)))
            coordinator.enqueue(SnapshotCommand.MarkDirty(chatId = ChatId(100L)))
            coordinator.enqueue(SnapshotCommand.MarkDirty(chatId = ChatId(100L)))
            coordinator.enqueue(SnapshotCommand.Drain(budget = 10))

            assertEquals(1, coordinator.dirtyRoomCount())

            runCurrent()

            assertEquals(0, coordinator.dirtyRoomCount())
            assertEquals(1, diffEngine.calls)
            scope.cancel()
        }
}

private class StubSnapshotReader(
    private val roomsProvider: () -> List<ChatId>,
    private val snapshots: Map<Long, List<RoomSnapshotData>>,
) : RoomSnapshotReader {
    constructor(
        rooms: List<Long>,
        snapshots: Map<Long, List<RoomSnapshotData>>,
    ) : this({ rooms.map(::ChatId) }, snapshots)

    private val indexes = mutableMapOf<Long, Int>()
    val snapshotCalls = mutableListOf<Long>()

    override fun listRoomChatIds(): List<ChatId> = roomsProvider()

    override fun snapshot(chatId: ChatId): RoomSnapshotReadResult {
        snapshotCalls += chatId.value
        if (chatId !in roomsProvider()) {
            return RoomSnapshotReadResult.Missing
        }
        val list = snapshots[chatId.value] ?: return RoomSnapshotReadResult.Missing
        val idx = indexes[chatId.value] ?: 0
        val safeIdx = minOf(idx, list.lastIndex)
        indexes[chatId.value] = safeIdx + 1
        return RoomSnapshotReadResult.Present(list[safeIdx])
    }
}

private class RecordingDiffEngine : RoomDiffEngine {
    var calls = 0
    val diffedChatIds = mutableListOf<Long>()
    var missingCalls = 0
    var restoredCalls = 0
    val missingChatIds = mutableListOf<Long>()
    val restoredChatIds = mutableListOf<Long>()

    override fun diff(
        prev: RoomSnapshotData,
        curr: RoomSnapshotData,
    ): List<RoomEvent> {
        calls++
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

    override fun diffMissing(prev: RoomSnapshotData): List<RoomEvent> {
        calls++
        missingCalls++
        missingChatIds += prev.chatId.value
        val left = prev.memberIds.firstOrNull() ?: return emptyList()
        return listOf(
            MemberEvent(
                event = "leave",
                chatId = prev.chatId.value,
                linkId = prev.linkId?.value,
                userId = left.value,
                nickname = prev.nicknames[left],
                estimated = true,
                timestamp = 1L,
            ),
        )
    }

    override fun diffRestored(curr: RoomSnapshotData): List<RoomEvent> {
        calls++
        restoredCalls++
        restoredChatIds += curr.chatId.value
        val joined = curr.memberIds.firstOrNull() ?: return emptyList()
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

private class RecordingEmitter :
    SnapshotEventEmitter(
        bus = SseEventBus(bufferSize = 100),
        routingGateway = null,
    ) {
    var emitCalls = 0
    val emittedEvents = mutableListOf<RoomEvent>()

    override fun emit(events: List<RoomEvent>) {
        emitCalls++
        emittedEvents.addAll(events)
        super.emit(events)
    }
}
