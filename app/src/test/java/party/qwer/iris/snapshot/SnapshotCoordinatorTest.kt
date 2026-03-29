package party.qwer.iris.snapshot

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import party.qwer.iris.RoomSnapshotData
import party.qwer.iris.SseEventBus
import party.qwer.iris.model.MemberEvent
import kotlin.test.Test
import kotlin.test.assertEquals

class SnapshotCoordinatorTest {
    private fun snapshot(
        chatId: Long,
        members: Set<Long>,
        nicknames: Map<Long, String> = emptyMap(),
    ): RoomSnapshotData =
        RoomSnapshotData(
            chatId = chatId,
            linkId = chatId + 1000L,
            memberIds = members,
            blindedIds = emptySet(),
            nicknames = nicknames,
            roles = members.associateWith { 2 },
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
            coordinator.send(SnapshotCommand.MarkDirty(chatId = 100L))
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
            coordinator.send(SnapshotCommand.MarkDirty(chatId = 100L))
            coordinator.send(SnapshotCommand.MarkDirty(chatId = 100L))
            coordinator.send(SnapshotCommand.MarkDirty(chatId = 100L))
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
            coordinator.send(SnapshotCommand.MarkDirty(chatId = 100L))
            coordinator.send(SnapshotCommand.MarkDirty(chatId = 200L))
            coordinator.send(SnapshotCommand.MarkDirty(chatId = 300L))
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
            coordinator.send(SnapshotCommand.MarkDirty(chatId = 100L))
            coordinator.send(SnapshotCommand.MarkDirty(chatId = 200L))
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

            coordinator.send(SnapshotCommand.MarkDirty(chatId = 100L))
            yield()
            coordinator.send(SnapshotCommand.Drain(budget = 10))
            yield()

            assertEquals(0, diffEngine.calls)
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
            coordinator.enqueue(SnapshotCommand.MarkDirty(chatId = 100L))
            yield()

            assertEquals(1, coordinator.dirtyRoomCount())

            coordinator.enqueue(SnapshotCommand.Drain(budget = 10))
            yield()

            assertEquals(0, coordinator.dirtyRoomCount())
            assertEquals(1, diffEngine.calls)
            scope.cancel()
        }
}

private class StubSnapshotReader(
    private val rooms: List<Long>,
    private val snapshots: Map<Long, List<RoomSnapshotData>>,
) : RoomSnapshotReader {
    private val indexes = mutableMapOf<Long, Int>()

    override fun listRoomChatIds(): List<Long> = rooms

    override fun snapshot(chatId: Long): RoomSnapshotData {
        val list = snapshots[chatId] ?: error("No snapshots for chatId=$chatId")
        val idx = indexes[chatId] ?: 0
        val safeIdx = minOf(idx, list.lastIndex)
        indexes[chatId] = safeIdx + 1
        return list[safeIdx]
    }
}

private class RecordingDiffEngine : RoomDiffEngine {
    var calls = 0
    val diffedChatIds = mutableListOf<Long>()

    override fun diff(
        prev: RoomSnapshotData,
        curr: RoomSnapshotData,
    ): List<Any> {
        calls++
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

private class RecordingEmitter :
    SnapshotEventEmitter(
        bus = SseEventBus(bufferSize = 100),
        routingGateway = null,
    ) {
    var emitCalls = 0

    override fun emit(events: List<Any>) {
        emitCalls++
        super.emit(events)
    }
}
