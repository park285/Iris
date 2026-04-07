package party.qwer.iris.snapshot

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import party.qwer.iris.SseEventBus
import party.qwer.iris.delivery.webhook.RoutingCommand
import party.qwer.iris.delivery.webhook.RoutingGateway
import party.qwer.iris.delivery.webhook.RoutingResult
import party.qwer.iris.model.MemberEvent
import party.qwer.iris.model.NicknameChangeEvent
import party.qwer.iris.model.ProfileChangeEvent
import party.qwer.iris.model.RoleChangeEvent
import party.qwer.iris.persistence.IrisDatabaseSchema
import party.qwer.iris.persistence.JdbcSqliteHelper
import party.qwer.iris.persistence.SqliteRoomEventStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SnapshotEventEmitterTest {
    @Test
    fun `emits MemberEvent to bus and routes to gateway`() {
        val bus = SseEventBus(bufferSize = 10)
        val gateway = RecordingGateway()
        val emitter = SnapshotEventEmitter(bus, gateway)

        val event: party.qwer.iris.model.RoomEvent =
            MemberEvent(
                event = "join",
                chatId = 100L,
                linkId = 1100L,
                userId = 1L,
                nickname = "Alice",
                estimated = false,
                timestamp = 1L,
            )
        emitter.emit(listOf(event))

        val replayed = bus.replayFrom(0)
        assertEquals(1, replayed.size)
        assertTrue(replayed[0].second.contains("\"event\":\"join\""))
        assertEquals(1, gateway.commands.size)
        assertEquals("100", gateway.commands[0].room)
        assertEquals("iris-system", gateway.commands[0].sender)
        assertEquals("member_event", gateway.commands[0].messageType)
    }

    @Test
    fun `emits NicknameChangeEvent to bus`() {
        val bus = SseEventBus(bufferSize = 10)
        val gateway = RecordingGateway()
        val emitter = SnapshotEventEmitter(bus, gateway)

        val event =
            NicknameChangeEvent(
                chatId = 200L,
                linkId = 1200L,
                userId = 2L,
                oldNickname = "Bob",
                newNickname = "Bobby",
                timestamp = 2L,
            )
        emitter.emit(listOf(event))

        val replayed = bus.replayFrom(0)
        assertEquals(1, replayed.size)
        assertTrue(replayed[0].second.contains("\"oldNickname\":\"Bob\""))
        assertEquals(1, gateway.commands.size)
        assertEquals("200", gateway.commands[0].room)
        assertEquals("nickname_change", gateway.commands[0].messageType)
        val eventPayload = assertNotNull(gateway.commands[0].eventPayload)
        assertEquals("Bob", eventPayload.jsonObject["oldNickname"]?.jsonPrimitive?.content)
        assertEquals("Bobby", eventPayload.jsonObject["newNickname"]?.jsonPrimitive?.content)
    }

    @Test
    fun `emits RoleChangeEvent to gateway with subtype message type`() {
        val bus = SseEventBus(bufferSize = 10)
        val gateway = RecordingGateway()
        val emitter = SnapshotEventEmitter(bus, gateway)

        val event =
            RoleChangeEvent(
                chatId = 300L,
                linkId = 1300L,
                userId = 3L,
                oldRole = "member",
                newRole = "admin",
                timestamp = 3L,
            )
        emitter.emit(listOf(event))

        assertEquals(1, gateway.commands.size)
        assertEquals("role_change", gateway.commands[0].messageType)
    }

    @Test
    fun `emits ProfileChangeEvent to gateway with subtype message type`() {
        val bus = SseEventBus(bufferSize = 10)
        val gateway = RecordingGateway()
        val emitter = SnapshotEventEmitter(bus, gateway)

        val event =
            ProfileChangeEvent(
                chatId = 400L,
                linkId = 1400L,
                userId = 4L,
                timestamp = 4L,
                nickname = "Dana",
                oldProfileImageUrl = "https://example.com/old.png",
                newProfileImageUrl = "https://example.com/new.png",
            )
        emitter.emit(listOf(event))

        assertEquals(1, gateway.commands.size)
        assertEquals("profile_change", gateway.commands[0].messageType)
        val eventPayload = assertNotNull(gateway.commands[0].eventPayload)
        assertEquals("Dana", eventPayload.jsonObject["nickname"]?.jsonPrimitive?.content)
        assertEquals("https://example.com/new.png", eventPayload.jsonObject["newProfileImageUrl"]?.jsonPrimitive?.content)
    }

    @Test
    fun `typed room events expose common chat id contract`() {
        val event =
            MemberEvent(
                event = "join",
                chatId = 100L,
                linkId = 1100L,
                userId = 1L,
                nickname = "Alice",
                estimated = false,
                timestamp = 1L,
            )

        assertEquals(100L, event.chatId)
    }

    @Test
    fun `emits multiple events in order`() {
        val bus = SseEventBus(bufferSize = 10)
        val gateway = RecordingGateway()
        val emitter = SnapshotEventEmitter(bus, gateway)

        val events =
            listOf(
                MemberEvent(
                    event = "join",
                    chatId = 100L,
                    linkId = 1100L,
                    userId = 1L,
                    nickname = "A",
                    estimated = false,
                    timestamp = 1L,
                ),
                MemberEvent(
                    event = "leave",
                    chatId = 100L,
                    linkId = 1100L,
                    userId = 2L,
                    nickname = "B",
                    estimated = true,
                    timestamp = 2L,
                ),
            )
        emitter.emit(events)

        assertEquals(2, bus.replayFrom(0).size)
        assertEquals(2, gateway.commands.size)
    }

    @Test
    fun `tolerates null gateway`() {
        val bus = SseEventBus(bufferSize = 10)
        val emitter = SnapshotEventEmitter(bus, routingGateway = null)

        val event =
            MemberEvent(
                event = "join",
                chatId = 100L,
                linkId = 1100L,
                userId = 1L,
                nickname = "Alice",
                estimated = false,
                timestamp = 1L,
            )
        emitter.emit(listOf(event))

        assertEquals(1, bus.replayFrom(0).size)
    }

    // --- RoomEventStore 통합 테스트 ---

    @Test
    fun `emitter with store persists all room event types`() {
        val bus = SseEventBus(bufferSize = 10)
        val helper = JdbcSqliteHelper.inMemory()
        IrisDatabaseSchema.createRoomEventsTable(helper)
        val store = SqliteRoomEventStore(helper)
        val emitter = SnapshotEventEmitter(bus, routingGateway = null, eventStore = store)

        val events: List<party.qwer.iris.model.RoomEvent> =
            listOf(
                MemberEvent(event = "join", chatId = 100L, linkId = 1100L, userId = 1L, nickname = "Alice", timestamp = 1000L),
                NicknameChangeEvent(chatId = 100L, linkId = 1100L, userId = 2L, oldNickname = "Bob", newNickname = "Bobby", timestamp = 2000L),
                RoleChangeEvent(chatId = 200L, linkId = 1200L, userId = 3L, oldRole = "member", newRole = "admin", timestamp = 3000L),
                ProfileChangeEvent(chatId = 200L, linkId = 1200L, userId = 4L, timestamp = 4000L),
            )
        emitter.emit(events)

        // room 100 이벤트 2개
        val room100 = store.listByChatId(100L, limit = 100)
        assertEquals(2, room100.size)
        assertEquals("member_event", room100[0].eventType)
        assertEquals(1L, room100[0].userId)
        assertEquals("nickname_change", room100[1].eventType)
        assertEquals(2L, room100[1].userId)

        // room 200 이벤트 2개
        val room200 = store.listByChatId(200L, limit = 100)
        assertEquals(2, room200.size)
        assertEquals("role_change", room200[0].eventType)
        assertEquals("profile_change", room200[1].eventType)

        // payload에 원본 JSON 포함 확인
        assertTrue(room100[0].payload.contains("\"event\":\"join\""))
        assertTrue(room100[1].payload.contains("\"oldNickname\":\"Bob\""))

        // createdAt은 이벤트 timestamp(초)를 밀리초로 변환한 값
        assertEquals(1_000_000L, room100[0].createdAt)
        assertEquals(2_000_000L, room100[1].createdAt)
        assertEquals(3_000_000L, room200[0].createdAt)
        assertEquals(4_000_000L, room200[1].createdAt)

        helper.close()
    }

    @Test
    fun `emitter without store still works`() {
        val bus = SseEventBus(bufferSize = 10)
        val emitter = SnapshotEventEmitter(bus, routingGateway = null, eventStore = null)

        val event = MemberEvent(event = "join", chatId = 100L, linkId = 1100L, userId = 1L, nickname = "Alice", timestamp = 1L)
        emitter.emit(listOf(event))

        assertEquals(1, bus.replayFrom(0).size)
    }

    @Test
    fun `emitter persists to store and emits to bus simultaneously`() {
        val bus = SseEventBus(bufferSize = 10)
        val gateway = RecordingGateway()
        val helper = JdbcSqliteHelper.inMemory()
        IrisDatabaseSchema.createRoomEventsTable(helper)
        val store = SqliteRoomEventStore(helper)
        val emitter = SnapshotEventEmitter(bus, gateway, eventStore = store)

        val event = MemberEvent(event = "leave", chatId = 300L, linkId = 1300L, userId = 5L, nickname = "Charlie", estimated = true, timestamp = 5000L)
        emitter.emit(listOf(event))

        // 3가지 경로 모두 동작 확인
        assertEquals(1, bus.replayFrom(0).size)
        assertEquals(1, gateway.commands.size)
        assertEquals(1, store.listByChatId(300L, limit = 100).size)

        helper.close()
    }

    @Test
    fun `retry later routing prevents room event persistence`() {
        val bus = SseEventBus(bufferSize = 10)
        val helper = JdbcSqliteHelper.inMemory()
        IrisDatabaseSchema.createRoomEventsTable(helper)
        val store = SqliteRoomEventStore(helper)
        val emitter = SnapshotEventEmitter(bus, RetryLaterGateway(), eventStore = store)

        val event = NicknameChangeEvent(chatId = 100L, linkId = 1100L, userId = 2L, oldNickname = "Bob", newNickname = "Bobby", timestamp = 2000L)

        assertFailsWith<IllegalStateException> {
            emitter.emit(listOf(event))
        }
        assertEquals(emptyList(), store.listByChatId(100L, limit = 100))
        assertEquals(emptyList(), bus.replayFrom(0))

        helper.close()
    }

    @Test
    fun `skipped routable nickname event prevents room event persistence`() {
        val bus = SseEventBus(bufferSize = 10)
        val helper = JdbcSqliteHelper.inMemory()
        IrisDatabaseSchema.createRoomEventsTable(helper)
        val store = SqliteRoomEventStore(helper)
        val emitter = SnapshotEventEmitter(bus, SkippedGateway(), eventStore = store)

        val event = NicknameChangeEvent(chatId = 100L, linkId = 1100L, userId = 2L, oldNickname = "Bob", newNickname = "Bobby", timestamp = 2000L)

        assertFailsWith<IllegalStateException> {
            emitter.emit(listOf(event))
        }
        assertEquals(emptyList(), store.listByChatId(100L, limit = 100))
        assertEquals(emptyList(), bus.replayFrom(0))

        helper.close()
    }
}

private class RecordingGateway : RoutingGateway {
    val commands = mutableListOf<RoutingCommand>()

    override fun route(command: RoutingCommand): RoutingResult {
        commands += command
        return RoutingResult.ACCEPTED
    }

    override fun close() {}
}

private class RetryLaterGateway : RoutingGateway {
    override fun route(command: RoutingCommand): RoutingResult = RoutingResult.RETRY_LATER

    override fun close() {}
}

private class SkippedGateway : RoutingGateway {
    override fun route(command: RoutingCommand): RoutingResult = RoutingResult.SKIPPED

    override fun close() {}
}
