package party.qwer.iris

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import party.qwer.iris.delivery.webhook.RoutingCommand
import party.qwer.iris.delivery.webhook.RoutingGateway
import party.qwer.iris.delivery.webhook.RoutingResult
import party.qwer.iris.model.RoomEventRecord
import party.qwer.iris.persistence.InMemoryLiveRoomMemberPlanStore
import party.qwer.iris.persistence.InMemoryMemberIdentityStateStore
import party.qwer.iris.persistence.RoomEventStore
import party.qwer.iris.persistence.StoredLiveRoomMemberIdentity
import party.qwer.iris.persistence.StoredLiveRoomMemberPlan
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
                    intervalMs = 100L,
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
                    intervalMs = 100L,
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
                    intervalMs = 100L,
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
    fun `observer startup tolerates one room snapshot failure`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val reader =
                ScriptedMemberIdentitySnapshotReader(
                    rooms = listOf(100L, 200L),
                    snapshots =
                        mapOf(
                            100L to listOf(SnapshotStep.Failure(IllegalStateException("boom"))),
                            200L to
                                listOf(
                                    SnapshotStep.Present(snapshot(chatId = 200L, nicknames = mapOf(2L to "Bob"))),
                                    SnapshotStep.Present(snapshot(chatId = 200L, nicknames = mapOf(2L to "Bobby"))),
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
            observer.stopSuspend()

            val event = eventStore.insertedEvents.single { it.eventType == "nickname_change" }
            assertTrue(event.payload.contains("\"oldNickname\":\"Bob\""))
            assertTrue(event.payload.contains("\"newNickname\":\"Bobby\""))
        }

    @Test
    fun `observer poll continues after one room fails`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val reader =
                ScriptedMemberIdentitySnapshotReader(
                    rooms = listOf(100L, 200L),
                    snapshots =
                        mapOf(
                            100L to
                                listOf(
                                    SnapshotStep.Present(snapshot(chatId = 100L, nicknames = mapOf(1L to "Alice"))),
                                    SnapshotStep.Failure(IllegalStateException("boom")),
                                ),
                            200L to
                                listOf(
                                    SnapshotStep.Present(snapshot(chatId = 200L, nicknames = mapOf(2L to "Bob"))),
                                    SnapshotStep.Present(snapshot(chatId = 200L, nicknames = mapOf(2L to "Bobby"))),
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
            observer.stopSuspend()

            val event = eventStore.insertedEvents.single { it.eventType == "nickname_change" }
            assertTrue(event.payload.contains("\"oldNickname\":\"Bob\""))
            assertTrue(event.payload.contains("\"newNickname\":\"Bobby\""))
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

    @Test
    fun `observer prefers live bridge snapshot over stale db nickname state`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val reader =
                StubMemberIdentitySnapshotReader(
                    rooms = listOf(100L),
                    snapshots = mapOf(100L to listOf(snapshot(chatId = 100L, nicknames = mapOf(1L to "Alice")))),
                )
            var liveCallCount = 0
            val bus = SseEventBus(bufferSize = 16)
            val eventStore = RecordingMemberIdentityRoomEventStore()
            val observer =
                MemberIdentityObserver(
                    roomSnapshotReader = reader,
                    emitter = SnapshotEventEmitter(bus, TestRoutingGateway(), eventStore = eventStore),
                    stateStore = InMemoryMemberIdentityStateStore(),
                    roomEventStore = eventStore,
                    liveMemberSnapshotProvider =
                        LiveRoomMemberSnapshotProvider { chatId, _, _ ->
                            liveCallCount += 1
                            LiveRoomMemberSnapshot(
                                chatId = chatId,
                                scannedAtEpochMs = testScheduler.currentTime,
                                members =
                                    mapOf(
                                        UserId(1L) to
                                            LiveRoomMember(
                                                userId = UserId(1L),
                                                nickname = if (liveCallCount == 1) "Alice" else "Alice Updated",
                                            ),
                                    ),
                                confidence = LiveSnapshotConfidence.HIGH,
                            )
                        },
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
    fun `observer startup priming does not query live bridge snapshot`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val reader =
                StubMemberIdentitySnapshotReader(
                    rooms = listOf(100L),
                    snapshots = mapOf(100L to listOf(snapshot(chatId = 100L, nicknames = mapOf(1L to "Alice")))),
                )
            var liveCallCount = 0
            val observer =
                MemberIdentityObserver(
                    roomSnapshotReader = reader,
                    emitter = SnapshotEventEmitter(SseEventBus(bufferSize = 8), TestRoutingGateway(), eventStore = null),
                    stateStore = InMemoryMemberIdentityStateStore(),
                    liveMemberSnapshotProvider =
                        LiveRoomMemberSnapshotProvider { chatId, _, _ ->
                            liveCallCount += 1
                            LiveRoomMemberSnapshot(
                                chatId = chatId,
                                scannedAtEpochMs = testScheduler.currentTime,
                                members = mapOf(UserId(1L) to LiveRoomMember(userId = UserId(1L), nickname = "Alice")),
                                confidence = LiveSnapshotConfidence.HIGH,
                            )
                        },
                    intervalMs = 50L,
                    clock = { testScheduler.currentTime },
                    dispatcher = dispatcher,
                )

            observer.start()

            assertEquals(0, liveCallCount)

            observer.stopSuspend()
        }

    @Test
    fun `observer falls back to db snapshot when live bridge snapshot fails`() =
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
                    roomEventStore = eventStore,
                    liveMemberSnapshotProvider = LiveRoomMemberSnapshotProvider { _, _, _ -> error("bridge down") },
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
    fun `observer uses stored member baseline when db snapshot is empty but live bridge has nickname`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val reader =
                StubMemberIdentitySnapshotReader(
                    rooms = listOf(100L),
                    snapshots = mapOf(100L to listOf(snapshot(chatId = 100L, nicknames = emptyMap(), memberIds = emptySet()))),
                )
            val bus = SseEventBus(bufferSize = 16)
            val eventStore = RecordingMemberIdentityRoomEventStore()
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
                    liveMemberSnapshotProvider =
                        LiveRoomMemberSnapshotProvider { chatId, expectedMembers, _ ->
                            assertEquals(setOf(UserId(1L)), expectedMembers.map { it.userId }.toSet())
                            assertEquals("Alice", expectedMembers.single().nickname)
                            LiveRoomMemberSnapshot(
                                chatId = chatId,
                                scannedAtEpochMs = testScheduler.currentTime,
                                members =
                                    mapOf(
                                        UserId(1L) to LiveRoomMember(userId = UserId(1L), nickname = "Alice Updated"),
                                    ),
                                confidence = LiveSnapshotConfidence.HIGH,
                            )
                        },
                    intervalMs = 50L,
                    clock = { testScheduler.currentTime },
                    dispatcher = dispatcher,
                )

            observer.start()
            advanceTimeBy(60L)
            runCurrent()
            observer.stopSuspend()

            val event = eventStore.insertedEvents.single { it.eventType == "nickname_change" }
            assertTrue(event.payload.contains("\"oldNickname\":\"Alice\""))
            assertTrue(event.payload.contains("\"newNickname\":\"Alice Updated\""))
        }

    @Test
    fun `observer suppresses reverse rename when live snapshot temporarily disappears`() =
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
            var liveCallCount = 0
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
                    liveMemberSnapshotProvider =
                        LiveRoomMemberSnapshotProvider { chatId, _, _ ->
                            liveCallCount += 1
                            when (liveCallCount) {
                                1 ->
                                    LiveRoomMemberSnapshot(
                                        chatId = chatId,
                                        scannedAtEpochMs = testScheduler.currentTime,
                                        members =
                                            mapOf(
                                                UserId(1L) to LiveRoomMember(userId = UserId(1L), nickname = "Alice Updated"),
                                            ),
                                        confidence = LiveSnapshotConfidence.HIGH,
                                    )

                                else -> null
                            }
                        },
                    intervalMs = 100L,
                    clock = { testScheduler.currentTime },
                    dispatcher = dispatcher,
                )

            observer.start()
            advanceTimeBy(60L)
            runCurrent()
            advanceTimeBy(60L)
            runCurrent()
            observer.stopSuspend()

            val nicknameEvents = eventStore.insertedEvents.filter { it.eventType == "nickname_change" }
            assertEquals(1, nicknameEvents.size, nicknameEvents.joinToString(separator = " | ") { it.payload })
            assertTrue(nicknameEvents.single().payload.contains("\"newNickname\":\"Alice Updated\""))
        }

    @Test
    fun `observer ignores persisted internal artifact nicknames on startup`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val reader =
                StubMemberIdentitySnapshotReader(
                    rooms = listOf(100L),
                    snapshots = mapOf(100L to listOf(snapshot(chatId = 100L, nicknames = mapOf(1L to "박준우")))),
                )
            val bus = SseEventBus(bufferSize = 16)
            val eventStore = RecordingMemberIdentityRoomEventStore()
            val observer =
                MemberIdentityObserver(
                    roomSnapshotReader = reader,
                    emitter = SnapshotEventEmitter(bus, TestRoutingGateway(), eventStore = eventStore),
                    stateStore =
                        InMemoryMemberIdentityStateStore().apply {
                            save(ChatId(100L), mapOf(UserId(1L) to "openLinkChatMemberIdBackup"))
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

            assertEquals(0, eventStore.insertedEvents.count { it.eventType == "nickname_change" })
        }

    @Test
    fun `observer does not emit low confidence live notice candidate without corroboration`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val reader =
                StubMemberIdentitySnapshotReader(
                    rooms = listOf(100L),
                    snapshots = mapOf(100L to listOf(snapshot(chatId = 100L, nicknames = mapOf(1L to "Alice")))),
                )
            val eventStore = RecordingMemberIdentityRoomEventStore()
            val observer =
                MemberIdentityObserver(
                    roomSnapshotReader = reader,
                    emitter = SnapshotEventEmitter(SseEventBus(bufferSize = 16), TestRoutingGateway(), eventStore = eventStore),
                    stateStore =
                        InMemoryMemberIdentityStateStore().apply {
                            save(ChatId(100L), mapOf(UserId(1L) to "Alice"))
                        },
                    roomEventStore = eventStore,
                    liveMemberSnapshotProvider =
                        LiveRoomMemberSnapshotProvider { chatId, _, _ ->
                            LiveRoomMemberSnapshot(
                                chatId = chatId,
                                scannedAtEpochMs = testScheduler.currentTime,
                                members = mapOf(UserId(1L) to LiveRoomMember(userId = UserId(1L), nickname = "Notice")),
                                confidence = LiveSnapshotConfidence.LOW,
                            )
                        },
                    intervalMs = 50L,
                    clock = { testScheduler.currentTime },
                    dispatcher = dispatcher,
                )

            observer.start()
            advanceTimeBy(60L)
            runCurrent()
            observer.stopSuspend()

            assertEquals(0, eventStore.insertedEvents.count { it.eventType == "nickname_change" })
        }

    @Test
    fun `observer confirms low confidence live candidate when db later matches`() =
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
            var liveCallCount = 0
            val eventStore = RecordingMemberIdentityRoomEventStore()
            val observer =
                MemberIdentityObserver(
                    roomSnapshotReader = reader,
                    emitter = SnapshotEventEmitter(SseEventBus(bufferSize = 16), TestRoutingGateway(), eventStore = eventStore),
                    stateStore =
                        InMemoryMemberIdentityStateStore().apply {
                            save(ChatId(100L), mapOf(UserId(1L) to "Alice"))
                        },
                    roomEventStore = eventStore,
                    liveMemberSnapshotProvider =
                        LiveRoomMemberSnapshotProvider { chatId, _, _ ->
                            liveCallCount += 1
                            when (liveCallCount) {
                                1 ->
                                    LiveRoomMemberSnapshot(
                                        chatId = chatId,
                                        scannedAtEpochMs = testScheduler.currentTime,
                                        members = mapOf(UserId(1L) to LiveRoomMember(userId = UserId(1L), nickname = "Alice Updated")),
                                        confidence = LiveSnapshotConfidence.LOW,
                                    )

                                else -> null
                            }
                        },
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
    fun `observer does not persist low confidence plan from partial member coverage`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val reader =
                StubMemberIdentitySnapshotReader(
                    rooms = listOf(100L),
                    snapshots =
                        mapOf(
                            100L to
                                listOf(
                                    snapshot(chatId = 100L, nicknames = mapOf(1L to "Alice", 2L to "Bob")),
                                    snapshot(chatId = 100L, nicknames = mapOf(1L to "Alice", 2L to "Bob")),
                                ),
                        ),
                )
            val selectedPlan =
                LiveRoomMemberExtractionPlan(
                    containerPath = "$.members",
                    sourceClassName = "FakeMember",
                    userIdPath = "id",
                    nicknamePath = "profile.nickname",
                    fingerprint = "$.members|FakeMember|id|profile.nickname",
                )
            val planStore = InMemoryLiveRoomMemberPlanStore()
            val capturedPlans = mutableListOf<LiveRoomMemberExtractionPlan?>()
            val observer =
                MemberIdentityObserver(
                    roomSnapshotReader = reader,
                    emitter = SnapshotEventEmitter(SseEventBus(bufferSize = 16), TestRoutingGateway(), eventStore = null),
                    stateStore =
                        InMemoryMemberIdentityStateStore().apply {
                            save(ChatId(100L), mapOf(UserId(1L) to "Alice", UserId(2L) to "Bob"))
                        },
                    liveRoomMemberPlanStore = planStore,
                    liveMemberSnapshotProvider =
                        LiveRoomMemberSnapshotProvider { chatId, _, preferredPlan ->
                            capturedPlans += preferredPlan
                            LiveRoomMemberSnapshot(
                                chatId = chatId,
                                scannedAtEpochMs = testScheduler.currentTime,
                                members =
                                    mapOf(
                                        UserId(1L) to LiveRoomMember(userId = UserId(1L), nickname = "Alice"),
                                    ),
                                selectedPlan = selectedPlan,
                                confidence = LiveSnapshotConfidence.LOW,
                            )
                        },
                    intervalMs = 100L,
                    clock = { testScheduler.currentTime },
                    dispatcher = dispatcher,
                )

            observer.start()
            advanceTimeBy(60L)
            runCurrent()
            advanceTimeBy(60L)
            runCurrent()
            observer.stopSuspend()

            assertEquals(listOf<LiveRoomMemberExtractionPlan?>(null, null), capturedPlans)
            assertTrue(planStore.loadAll().isEmpty())
        }

    @Test
    fun `observer does not persist low confidence plan when db snapshot omits confirmed members`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val reader =
                StubMemberIdentitySnapshotReader(
                    rooms = listOf(100L),
                    snapshots =
                        mapOf(
                            100L to
                                listOf(
                                    snapshot(chatId = 100L, nicknames = mapOf(1L to "Alice"), memberIds = setOf(1L)),
                                    snapshot(chatId = 100L, nicknames = mapOf(1L to "Alice"), memberIds = setOf(1L)),
                                ),
                        ),
                )
            val selectedPlan =
                LiveRoomMemberExtractionPlan(
                    containerPath = "$.members",
                    sourceClassName = "FakeMember",
                    userIdPath = "id",
                    nicknamePath = "profile.nickname",
                    fingerprint = "$.members|FakeMember|id|profile.nickname",
                )
            val planStore = InMemoryLiveRoomMemberPlanStore()
            val capturedPlans = mutableListOf<LiveRoomMemberExtractionPlan?>()
            val observer =
                MemberIdentityObserver(
                    roomSnapshotReader = reader,
                    emitter = SnapshotEventEmitter(SseEventBus(bufferSize = 16), TestRoutingGateway(), eventStore = null),
                    stateStore =
                        InMemoryMemberIdentityStateStore().apply {
                            save(ChatId(100L), mapOf(UserId(1L) to "Alice", UserId(2L) to "Bob"))
                        },
                    liveRoomMemberPlanStore = planStore,
                    liveMemberSnapshotProvider =
                        LiveRoomMemberSnapshotProvider { chatId, _, preferredPlan ->
                            capturedPlans += preferredPlan
                            LiveRoomMemberSnapshot(
                                chatId = chatId,
                                scannedAtEpochMs = testScheduler.currentTime,
                                members =
                                    mapOf(
                                        UserId(1L) to LiveRoomMember(userId = UserId(1L), nickname = "Alice"),
                                    ),
                                selectedPlan = selectedPlan,
                                confidence = LiveSnapshotConfidence.LOW,
                            )
                        },
                    intervalMs = 100L,
                    clock = { testScheduler.currentTime },
                    dispatcher = dispatcher,
                )

            observer.start()
            advanceTimeBy(60L)
            runCurrent()
            advanceTimeBy(60L)
            runCurrent()
            observer.stopSuspend()

            assertEquals(listOf<LiveRoomMemberExtractionPlan?>(null, null), capturedPlans)
            assertTrue(planStore.loadAll().isEmpty())
        }

    @Test
    fun `observer persists low confidence plan when omitted confirmed member already left`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val reader =
                StubMemberIdentitySnapshotReader(
                    rooms = listOf(100L),
                    snapshots =
                        mapOf(
                            100L to
                                listOf(
                                    snapshot(chatId = 100L, nicknames = mapOf(1L to "Alice"), memberIds = setOf(1L)),
                                    snapshot(chatId = 100L, nicknames = mapOf(1L to "Alice"), memberIds = setOf(1L)),
                                ),
                        ),
                )
            val selectedPlan =
                LiveRoomMemberExtractionPlan(
                    containerPath = "$.members",
                    sourceClassName = "FakeMember",
                    userIdPath = "id",
                    nicknamePath = "profile.nickname",
                    fingerprint = "$.members|FakeMember|id|profile.nickname",
                )
            val planStore = InMemoryLiveRoomMemberPlanStore()
            val eventStore =
                RecordingMemberIdentityRoomEventStore().apply {
                    insert(
                        chatId = 100L,
                        eventType = "member_event",
                        userId = 2L,
                        payload = """{"type":"member_event","event":"leave","chatId":100,"userId":2,"nickname":"Bob","estimated":true,"timestamp":1}""",
                        createdAtMs = 1L,
                    )
                }
            val capturedPlans = mutableListOf<LiveRoomMemberExtractionPlan?>()
            val observer =
                MemberIdentityObserver(
                    roomSnapshotReader = reader,
                    emitter = SnapshotEventEmitter(SseEventBus(bufferSize = 16), TestRoutingGateway(), eventStore = eventStore),
                    stateStore =
                        InMemoryMemberIdentityStateStore().apply {
                            save(ChatId(100L), mapOf(UserId(1L) to "Alice", UserId(2L) to "Bob"))
                        },
                    liveRoomMemberPlanStore = planStore,
                    roomEventStore = eventStore,
                    liveMemberSnapshotProvider =
                        LiveRoomMemberSnapshotProvider { chatId, _, preferredPlan ->
                            capturedPlans += preferredPlan
                            LiveRoomMemberSnapshot(
                                chatId = chatId,
                                scannedAtEpochMs = testScheduler.currentTime,
                                members =
                                    mapOf(
                                        UserId(1L) to LiveRoomMember(userId = UserId(1L), nickname = "Alice"),
                                    ),
                                selectedPlan = selectedPlan,
                                confidence =
                                    if (preferredPlan == selectedPlan) {
                                        LiveSnapshotConfidence.HIGH
                                    } else {
                                        LiveSnapshotConfidence.LOW
                                    },
                                usedPreferredPlan = preferredPlan == selectedPlan,
                            )
                        },
                    intervalMs = 100L,
                    clock = { testScheduler.currentTime },
                    dispatcher = dispatcher,
                )

            observer.start()
            advanceTimeBy(60L)
            runCurrent()
            advanceTimeBy(60L)
            runCurrent()
            observer.stopSuspend()

            assertEquals(listOf(null, selectedPlan), capturedPlans)
            assertEquals(selectedPlan, planStore.loadAll().getValue(ChatId(100L)).plan)
        }

    @Test
    fun `observer persists corroborated low confidence plan so next rename can confirm immediately`() =
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
            val selectedPlan =
                LiveRoomMemberExtractionPlan(
                    containerPath = "$.members",
                    sourceClassName = "FakeMember",
                    userIdPath = "id",
                    nicknamePath = "profile.nickname",
                    fingerprint = "$.members|FakeMember|id|profile.nickname",
                )
            val planStore = InMemoryLiveRoomMemberPlanStore()
            val capturedPlans = mutableListOf<LiveRoomMemberExtractionPlan?>()
            var liveCallCount = 0
            val eventStore = RecordingMemberIdentityRoomEventStore()
            val observer =
                MemberIdentityObserver(
                    roomSnapshotReader = reader,
                    emitter = SnapshotEventEmitter(SseEventBus(bufferSize = 16), TestRoutingGateway(), eventStore = eventStore),
                    stateStore =
                        InMemoryMemberIdentityStateStore().apply {
                            save(ChatId(100L), mapOf(UserId(1L) to "Alice"))
                        },
                    liveRoomMemberPlanStore = planStore,
                    roomEventStore = eventStore,
                    liveMemberSnapshotProvider =
                        LiveRoomMemberSnapshotProvider { chatId, _, preferredPlan ->
                            capturedPlans += preferredPlan
                            liveCallCount += 1
                            when (liveCallCount) {
                                1 ->
                                    LiveRoomMemberSnapshot(
                                        chatId = chatId,
                                        scannedAtEpochMs = testScheduler.currentTime,
                                        members =
                                            mapOf(
                                                UserId(1L) to LiveRoomMember(userId = UserId(1L), nickname = "Alice"),
                                            ),
                                        selectedPlan = selectedPlan,
                                        confidence = LiveSnapshotConfidence.LOW,
                                    )

                                2 ->
                                    LiveRoomMemberSnapshot(
                                        chatId = chatId,
                                        scannedAtEpochMs = testScheduler.currentTime,
                                        members =
                                            mapOf(
                                                UserId(1L) to LiveRoomMember(userId = UserId(1L), nickname = "Alice Updated"),
                                            ),
                                        selectedPlan = selectedPlan,
                                        confidence =
                                            if (preferredPlan == selectedPlan) {
                                                LiveSnapshotConfidence.HIGH
                                            } else {
                                                LiveSnapshotConfidence.LOW
                                            },
                                        usedPreferredPlan = preferredPlan == selectedPlan,
                                    )

                                else -> null
                            }
                        },
                    intervalMs = 100L,
                    clock = { testScheduler.currentTime },
                    dispatcher = dispatcher,
                )

            observer.start()
            advanceTimeBy(60L)
            runCurrent()
            advanceTimeBy(60L)
            runCurrent()
            observer.stopSuspend()

            assertEquals(listOf(null, selectedPlan), capturedPlans)
            assertEquals(selectedPlan, planStore.loadAll().getValue(ChatId(100L)).plan)

            val event = eventStore.insertedEvents.single { it.eventType == "nickname_change" }
            assertTrue(event.payload.contains("\"oldNickname\":\"Alice\""))
            assertTrue(event.payload.contains("\"newNickname\":\"Alice Updated\""))
        }

    @Test
    fun `observer persists preferred plan after medium confidence live snapshot`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val reader =
                StubMemberIdentitySnapshotReader(
                    rooms = listOf(100L),
                    snapshots = mapOf(100L to listOf(snapshot(chatId = 100L, nicknames = mapOf(1L to "Alice")))),
                )
            val planStore = InMemoryLiveRoomMemberPlanStore()
            val selectedPlan =
                LiveRoomMemberExtractionPlan(
                    containerPath = "$.members",
                    sourceClassName = "FakeMember",
                    userIdPath = "id",
                    nicknamePath = "profile.nickname",
                    fingerprint = "$.members|FakeMember|id|profile.nickname",
                )
            val observer =
                MemberIdentityObserver(
                    roomSnapshotReader = reader,
                    emitter = SnapshotEventEmitter(SseEventBus(bufferSize = 16), TestRoutingGateway(), eventStore = null),
                    stateStore =
                        InMemoryMemberIdentityStateStore().apply {
                            save(ChatId(100L), mapOf(UserId(1L) to "Alice"))
                        },
                    liveRoomMemberPlanStore = planStore,
                    liveMemberSnapshotProvider =
                        LiveRoomMemberSnapshotProvider { chatId, _, _ ->
                            LiveRoomMemberSnapshot(
                                chatId = chatId,
                                scannedAtEpochMs = testScheduler.currentTime,
                                members = mapOf(UserId(1L) to LiveRoomMember(userId = UserId(1L), nickname = "Alice")),
                                selectedPlan = selectedPlan,
                                confidence = LiveSnapshotConfidence.MEDIUM,
                                usedPreferredPlan = false,
                            )
                        },
                    intervalMs = 50L,
                    clock = { testScheduler.currentTime },
                    dispatcher = dispatcher,
                )

            observer.start()
            advanceTimeBy(60L)
            runCurrent()
            observer.stopSuspend()

            assertEquals(selectedPlan, planStore.loadAll().getValue(ChatId(100L)).plan)
        }

    @Test
    fun `observer loads preferred plan on startup and passes it to live bridge provider`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val reader =
                StubMemberIdentitySnapshotReader(
                    rooms = listOf(100L),
                    snapshots = mapOf(100L to listOf(snapshot(chatId = 100L, nicknames = mapOf(1L to "Alice")))),
                )
            val storedPlan =
                StoredLiveRoomMemberPlan(
                    plan =
                        LiveRoomMemberExtractionPlan(
                            containerPath = "$.members",
                            sourceClassName = "FakeMember",
                            userIdPath = "id",
                            nicknamePath = "profile.nickname",
                            fingerprint = "$.members|FakeMember|id|profile.nickname",
                        ),
                    lastKnownMembers = listOf(StoredLiveRoomMemberIdentity(userId = 1L, nickname = "Alice")),
                )
            val planStore =
                InMemoryLiveRoomMemberPlanStore().apply {
                    save(ChatId(100L), storedPlan)
                }
            var capturedPlan: LiveRoomMemberExtractionPlan? = null
            val observer =
                MemberIdentityObserver(
                    roomSnapshotReader = reader,
                    emitter = SnapshotEventEmitter(SseEventBus(bufferSize = 16), TestRoutingGateway(), eventStore = null),
                    stateStore =
                        InMemoryMemberIdentityStateStore().apply {
                            save(ChatId(100L), mapOf(UserId(1L) to "Alice"))
                        },
                    liveRoomMemberPlanStore = planStore,
                    liveMemberSnapshotProvider =
                        LiveRoomMemberSnapshotProvider { _, _, preferredPlan ->
                            capturedPlan = preferredPlan
                            null
                        },
                    intervalMs = 50L,
                    clock = { testScheduler.currentTime },
                    dispatcher = dispatcher,
                )

            observer.start()
            advanceTimeBy(60L)
            runCurrent()
            observer.stopSuspend()

            assertEquals(storedPlan.plan, capturedPlan)
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

private sealed interface SnapshotStep {
    data class Present(
        val snapshot: RoomSnapshotData,
    ) : SnapshotStep

    data class Failure(
        val error: Throwable,
    ) : SnapshotStep

    data object Missing : SnapshotStep
}

private class ScriptedMemberIdentitySnapshotReader(
    private val rooms: List<Long>,
    private val snapshots: Map<Long, List<SnapshotStep>>,
) : RoomSnapshotReader {
    private val indexes = mutableMapOf<Long, Int>()

    override fun listRoomChatIds(): List<ChatId> = rooms.map(::ChatId)

    override fun snapshot(chatId: ChatId): RoomSnapshotReadResult {
        val roomSnapshots = snapshots.getValue(chatId.value)
        val index = minOf(indexes[chatId.value] ?: 0, roomSnapshots.lastIndex)
        indexes[chatId.value] = index + 1
        return when (val step = roomSnapshots[index]) {
            is SnapshotStep.Present -> RoomSnapshotReadResult.Present(step.snapshot)
            is SnapshotStep.Failure -> throw step.error
            SnapshotStep.Missing -> RoomSnapshotReadResult.Missing
        }
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
