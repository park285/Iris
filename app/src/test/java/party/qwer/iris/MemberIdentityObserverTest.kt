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
import party.qwer.iris.model.RoomEventRecord
import party.qwer.iris.persistence.InMemoryMemberIdentityStateStore
import party.qwer.iris.persistence.RoomEventStore
import party.qwer.iris.snapshot.RoomSnapshotReadResult
import party.qwer.iris.snapshot.RoomSnapshotReader
import party.qwer.iris.snapshot.SnapshotEventEmitter
import party.qwer.iris.storage.ChatId
import party.qwer.iris.storage.LinkId
import party.qwer.iris.storage.UserId
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MemberIdentityObserverTest {
    @Test
    fun `observer emits nickname change with old and new nickname`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val reader =
                StubMemberIdentitySnapshotReader(
                    rooms = listOf(100L),
                    snapshots =
                        mapOf(
                            100L to
                                listOf(
                                    snapshot(chatId = 100L, nicknames = mapOf(1L to "Alice")),
                                    snapshot(chatId = 100L, nicknames = mapOf(1L to "Alice Updated")),
                                ),
                        ),
                )
            val bus = SseEventBus(bufferSize = 16)
            val eventStore = RecordingMemberIdentityRoomEventStore()
            val observer =
                MemberIdentityObserver(
                    roomSnapshotReader = reader,
                    emitter = SnapshotEventEmitter(bus, TestRoutingGateway(), eventStore = eventStore),
                    stateStore = InMemoryMemberIdentityStateStore(),
                    intervalMs = 50L,
                    clock = { testScheduler.currentTime },
                    dispatcher = dispatcher,
                )

            observer.start()
            advanceTimeBy(60L)
            runCurrent()
            advanceTimeBy(60L)
            runCurrent()
            observer.stopSuspend()

            val event = eventStore.insertedEvents.single { it.eventType == "nickname_change" }
            assertTrue(event.payload.contains("\"oldNickname\":\"Alice\""))
            assertTrue(event.payload.contains("\"newNickname\":\"Alice Updated\""))
        }

    @Test
    fun `observer does not emit on first sight or unchanged nickname`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val reader =
                StubMemberIdentitySnapshotReader(
                    rooms = listOf(100L),
                    snapshots =
                        mapOf(
                            100L to
                                listOf(
                                    snapshot(chatId = 100L, nicknames = mapOf(1L to "Alice")),
                                    snapshot(chatId = 100L, nicknames = mapOf(1L to "Alice")),
                                ),
                        ),
                )
            val bus = SseEventBus(bufferSize = 16)
            val eventStore = RecordingMemberIdentityRoomEventStore()
            val observer =
                MemberIdentityObserver(
                    roomSnapshotReader = reader,
                    emitter = SnapshotEventEmitter(bus, TestRoutingGateway(), eventStore = eventStore),
                    stateStore = InMemoryMemberIdentityStateStore(),
                    intervalMs = 50L,
                    clock = { testScheduler.currentTime },
                    dispatcher = dispatcher,
                )

            observer.start()
            advanceTimeBy(60L)
            runCurrent()
            advanceTimeBy(60L)
            runCurrent()
            observer.stopSuspend()

            assertEquals(emptyList(), eventStore.insertedEvents.map { it.eventType })
        }

    @Test
    fun `observer seeds missing rooms before polling so first later rename emits`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val reader =
                StubMemberIdentitySnapshotReader(
                    rooms = listOf(100L, 200L),
                    snapshots =
                        mapOf(
                            100L to listOf(snapshot(chatId = 100L, nicknames = mapOf(1L to "Alice"))),
                            200L to
                                listOf(
                                    snapshot(chatId = 200L, nicknames = mapOf(2L to "Bob")),
                                    snapshot(chatId = 200L, nicknames = mapOf(2L to "Bobby")),
                                ),
                        ),
                )
            val bus = SseEventBus(bufferSize = 16)
            val eventStore = RecordingMemberIdentityRoomEventStore()
            val observer =
                MemberIdentityObserver(
                    roomSnapshotReader = reader,
                    emitter = SnapshotEventEmitter(bus, TestRoutingGateway(), eventStore = eventStore),
                    stateStore = InMemoryMemberIdentityStateStore(),
                    intervalMs = 50L,
                    clock = { testScheduler.currentTime },
                    dispatcher = dispatcher,
                )

            observer.start()
            advanceTimeBy(60L)
            runCurrent()
            advanceTimeBy(60L)
            runCurrent()
            observer.stopSuspend()

            val event = eventStore.insertedEvents.single { it.eventType == "nickname_change" }
            assertTrue(event.payload.contains("\"oldNickname\":\"Bob\""))
            assertTrue(event.payload.contains("\"newNickname\":\"Bobby\""))
        }

    @Test
    fun `observer scans all rooms each tick so a rename is not delayed by cursor order`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val reader =
                StubMemberIdentitySnapshotReader(
                    rooms = listOf(100L, 200L, 300L),
                    snapshots =
                        mapOf(
                            100L to listOf(snapshot(chatId = 100L, nicknames = mapOf(1L to "Alice"))),
                            200L to listOf(snapshot(chatId = 200L, nicknames = mapOf(2L to "Bob"))),
                            300L to
                                listOf(
                                    snapshot(chatId = 300L, nicknames = mapOf(3L to "Carol")),
                                    snapshot(chatId = 300L, nicknames = mapOf(3L to "Carol Updated")),
                                ),
                        ),
                )
            val bus = SseEventBus(bufferSize = 16)
            val eventStore = RecordingMemberIdentityRoomEventStore()
            val observer =
                MemberIdentityObserver(
                    roomSnapshotReader = reader,
                    emitter = SnapshotEventEmitter(bus, TestRoutingGateway(), eventStore = eventStore),
                    stateStore = InMemoryMemberIdentityStateStore(),
                    intervalMs = 50L,
                    clock = { testScheduler.currentTime },
                    dispatcher = dispatcher,
                )

            observer.start()
            advanceTimeBy(60L)
            runCurrent()
            advanceTimeBy(60L)
            runCurrent()
            observer.stopSuspend()

            val event = eventStore.insertedEvents.single { it.eventType == "nickname_change" }
            assertTrue(event.payload.contains("\"oldNickname\":\"Carol\""))
            assertTrue(event.payload.contains("\"newNickname\":\"Carol Updated\""))
        }

    @Test
    fun `observer replays missed rename from last alerted nickname when current state already advanced`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val reader =
                StubMemberIdentitySnapshotReader(
                    rooms = listOf(100L),
                    snapshots =
                        mapOf(
                            100L to listOf(snapshot(chatId = 100L, nicknames = mapOf(1L to "Alice Updated"))),
                        ),
                )
            val bus = SseEventBus(bufferSize = 16)
            val eventStore = RecordingMemberIdentityRoomEventStore()
            eventStore.insert(
                chatId = 100L,
                eventType = "nickname_change",
                userId = 1L,
                payload = """{"chatId":100,"userId":1,"oldNickname":"Alice","newNickname":"Alice","timestamp":1}""",
                createdAtMs = 1L,
            )
            val stateStore =
                InMemoryMemberIdentityStateStore().apply {
                    save(ChatId(100L), mapOf(UserId(1L) to "Alice Updated"))
                }
            val observer =
                MemberIdentityObserver(
                    roomSnapshotReader = reader,
                    emitter = SnapshotEventEmitter(bus, TestRoutingGateway(), eventStore = eventStore),
                    stateStore = stateStore,
                    roomEventStore = eventStore,
                    intervalMs = 50L,
                    clock = { testScheduler.currentTime },
                    dispatcher = dispatcher,
                )

            observer.start()
            advanceTimeBy(60L)
            runCurrent()
            observer.stopSuspend()

            val event = eventStore.insertedEvents.last { it.eventType == "nickname_change" }
            assertTrue(event.payload.contains("\"oldNickname\":\"Alice\""))
            assertTrue(event.payload.contains("\"newNickname\":\"Alice Updated\""))
        }

    @Test
    fun `observer preserves nickname baseline across blank snapshot samples`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val reader =
                StubMemberIdentitySnapshotReader(
                    rooms = listOf(100L),
                    snapshots =
                        mapOf(
                            100L to
                                listOf(
                                    snapshot(chatId = 100L, nicknames = mapOf(1L to "Alice")),
                                    snapshot(chatId = 100L, nicknames = mapOf(1L to "")),
                                    snapshot(chatId = 100L, nicknames = mapOf(1L to "Alice Updated")),
                                ),
                        ),
                )
            val bus = SseEventBus(bufferSize = 16)
            val eventStore = RecordingMemberIdentityRoomEventStore()
            val observer =
                MemberIdentityObserver(
                    roomSnapshotReader = reader,
                    emitter = SnapshotEventEmitter(bus, TestRoutingGateway(), eventStore = eventStore),
                    stateStore = InMemoryMemberIdentityStateStore(),
                    roomEventStore = eventStore,
                    intervalMs = 50L,
                    clock = { testScheduler.currentTime },
                    dispatcher = dispatcher,
                )

            observer.start()
            advanceTimeBy(60L)
            runCurrent()
            advanceTimeBy(60L)
            runCurrent()
            advanceTimeBy(60L)
            runCurrent()
            observer.stopSuspend()

            val event = eventStore.insertedEvents.single { it.eventType == "nickname_change" }
            assertTrue(event.payload.contains("\"oldNickname\":\"Alice\""))
            assertTrue(event.payload.contains("\"newNickname\":\"Alice Updated\""))
        }

    @Test
    fun `observer does not duplicate when the latest alerted nickname already matches current`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val reader =
                StubMemberIdentitySnapshotReader(
                    rooms = listOf(100L),
                    snapshots =
                        mapOf(
                            100L to listOf(snapshot(chatId = 100L, nicknames = mapOf(1L to "Alice Updated"))),
                        ),
                )
            val bus = SseEventBus(bufferSize = 16)
            val eventStore = RecordingMemberIdentityRoomEventStore()
            eventStore.insert(
                chatId = 100L,
                eventType = "nickname_change",
                userId = 1L,
                payload = """{"chatId":100,"userId":1,"oldNickname":"Alice","newNickname":"Alice Updated","timestamp":1}""",
                createdAtMs = 1L,
            )
            val stateStore =
                InMemoryMemberIdentityStateStore().apply {
                    save(ChatId(100L), mapOf(UserId(1L) to "Alice"))
                }
            val observer =
                MemberIdentityObserver(
                    roomSnapshotReader = reader,
                    emitter = SnapshotEventEmitter(bus, TestRoutingGateway(), eventStore = eventStore),
                    stateStore = stateStore,
                    roomEventStore = eventStore,
                    intervalMs = 50L,
                    clock = { testScheduler.currentTime },
                    dispatcher = dispatcher,
                )

            observer.start()
            advanceTimeBy(60L)
            runCurrent()
            observer.stopSuspend()

            assertEquals(1, eventStore.insertedEvents.count { it.eventType == "nickname_change" })
        }

    @Test
    fun `observer refreshes alert history written after startup and suppresses duplicates`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val reader =
                StubMemberIdentitySnapshotReader(
                    rooms = listOf(100L),
                    snapshots =
                        mapOf(
                            100L to listOf(snapshot(chatId = 100L, nicknames = mapOf(1L to "Alice Updated"))),
                        ),
                )
            val bus = SseEventBus(bufferSize = 16)
            val eventStore = RecordingMemberIdentityRoomEventStore()
            val observer =
                MemberIdentityObserver(
                    roomSnapshotReader = reader,
                    emitter = SnapshotEventEmitter(bus, TestRoutingGateway(), eventStore = eventStore),
                    stateStore =
                        InMemoryMemberIdentityStateStore().apply {
                            save(ChatId(100L), mapOf(UserId(1L) to "Alice"))
                        },
                    roomEventStore = eventStore,
                    intervalMs = 50L,
                    clock = { testScheduler.currentTime },
                    dispatcher = dispatcher,
                )

            observer.start()
            eventStore.insert(
                chatId = 100L,
                eventType = "nickname_change",
                userId = 1L,
                payload = """{"chatId":100,"userId":1,"oldNickname":"Alice","newNickname":"Alice Updated","timestamp":1}""",
                createdAtMs = 1L,
            )
            advanceTimeBy(60L)
            runCurrent()
            observer.stopSuspend()

            assertEquals(1, eventStore.insertedEvents.count { it.eventType == "nickname_change" })
        }

    @Test
    fun `observer prefers stored baseline over stale alerted nickname`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val reader =
                StubMemberIdentitySnapshotReader(
                    rooms = listOf(100L),
                    snapshots =
                        mapOf(
                            100L to listOf(snapshot(chatId = 100L, nicknames = mapOf(1L to "Alice Next"))),
                        ),
                )
            val bus = SseEventBus(bufferSize = 16)
            val eventStore = RecordingMemberIdentityRoomEventStore()
            eventStore.insert(
                chatId = 100L,
                eventType = "nickname_change",
                userId = 1L,
                payload = """{"chatId":100,"userId":1,"oldNickname":"Alice","newNickname":"Stale Alert","timestamp":1}""",
                createdAtMs = 1L,
            )
            val observer =
                MemberIdentityObserver(
                    roomSnapshotReader = reader,
                    emitter = SnapshotEventEmitter(bus, TestRoutingGateway(), eventStore = eventStore),
                    stateStore =
                        InMemoryMemberIdentityStateStore().apply {
                            save(ChatId(100L), mapOf(UserId(1L) to "Alice"))
                        },
                    roomEventStore = eventStore,
                    intervalMs = 50L,
                    clock = { testScheduler.currentTime },
                    dispatcher = dispatcher,
                )

            observer.start()
            advanceTimeBy(60L)
            runCurrent()
            observer.stopSuspend()

            val event = eventStore.insertedEvents.last { it.eventType == "nickname_change" }
            assertTrue(event.payload.contains("\"oldNickname\":\"Alice\""))
            assertTrue(event.payload.contains("\"newNickname\":\"Alice Next\""))
        }

    @Test
    fun `observer detects rename from open member nicknames even when member ids lag`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val reader =
                StubMemberIdentitySnapshotReader(
                    rooms = listOf(100L),
                    snapshots =
                        mapOf(
                            100L to
                                listOf(
                                    snapshot(chatId = 100L, nicknames = mapOf(1L to "Alice"), memberIds = emptySet()),
                                    snapshot(chatId = 100L, nicknames = mapOf(1L to "Alice Updated"), memberIds = emptySet()),
                                ),
                        ),
                )
            val bus = SseEventBus(bufferSize = 16)
            val eventStore = RecordingMemberIdentityRoomEventStore()
            val observer =
                MemberIdentityObserver(
                    roomSnapshotReader = reader,
                    emitter = SnapshotEventEmitter(bus, TestRoutingGateway(), eventStore = eventStore),
                    stateStore = InMemoryMemberIdentityStateStore(),
                    roomEventStore = eventStore,
                    intervalMs = 50L,
                    clock = { testScheduler.currentTime },
                    dispatcher = dispatcher,
                )

            observer.start()
            advanceTimeBy(60L)
            runCurrent()
            advanceTimeBy(60L)
            runCurrent()
            observer.stopSuspend()

            val event = eventStore.insertedEvents.single { it.eventType == "nickname_change" }
            assertTrue(event.payload.contains("\"oldNickname\":\"Alice\""))
            assertTrue(event.payload.contains("\"newNickname\":\"Alice Updated\""))
        }

    private fun snapshot(
        chatId: Long,
        nicknames: Map<Long, String>,
        memberIds: Set<Long> = nicknames.keys,
    ): RoomSnapshotData =
        RoomSnapshotData(
            chatId = ChatId(chatId),
            linkId = LinkId(chatId + 1000L),
            memberIds = memberIds.map(::UserId).toSet(),
            blindedIds = emptySet(),
            nicknames = nicknames.map { (userId, nickname) -> UserId(userId) to nickname }.toMap(),
            roles = memberIds.associate { UserId(it) to 2 },
            profileImages = emptyMap(),
        )
}

private class StubMemberIdentitySnapshotReader(
    private val rooms: List<Long>,
    private val snapshots: Map<Long, List<RoomSnapshotData>>,
) : RoomSnapshotReader {
    private val indexes = mutableMapOf<Long, Int>()

    override fun listRoomChatIds(): List<ChatId> = rooms.map(::ChatId)

    override fun snapshot(chatId: ChatId): RoomSnapshotReadResult {
        val roomSnapshots = snapshots.getValue(chatId.value)
        val index = minOf(indexes[chatId.value] ?: 0, roomSnapshots.lastIndex)
        indexes[chatId.value] = index + 1
        return RoomSnapshotReadResult.Present(roomSnapshots[index])
    }
}

private class TestRoutingGateway : RoutingGateway {
    override fun route(command: RoutingCommand): RoutingResult = RoutingResult.ACCEPTED

    override fun close() {}
}

private class RecordingMemberIdentityRoomEventStore : RoomEventStore {
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

    override fun maxId(): Long = 0L

    override fun pruneOlderThan(cutoffMs: Long): Int = 0
}
