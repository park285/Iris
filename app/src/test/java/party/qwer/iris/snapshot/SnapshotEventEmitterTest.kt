package party.qwer.iris.snapshot

import party.qwer.iris.SseEventBus
import party.qwer.iris.delivery.webhook.RoutingCommand
import party.qwer.iris.delivery.webhook.RoutingGateway
import party.qwer.iris.delivery.webhook.RoutingResult
import party.qwer.iris.model.MemberEvent
import party.qwer.iris.model.NicknameChangeEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SnapshotEventEmitterTest {
    @Test
    fun `emits MemberEvent to bus and routes to gateway`() {
        val bus = SseEventBus(bufferSize = 10)
        val gateway = RecordingGateway()
        val emitter = SnapshotEventEmitter(bus, gateway)

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
    }

    @Test
    fun `skips unknown event types`() {
        val bus = SseEventBus(bufferSize = 10)
        val gateway = RecordingGateway()
        val emitter = SnapshotEventEmitter(bus, gateway)

        emitter.emit(listOf("not-an-event"))

        assertEquals(0, bus.replayFrom(0).size)
        assertEquals(0, gateway.commands.size)
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
}

private class RecordingGateway : RoutingGateway {
    val commands = mutableListOf<RoutingCommand>()

    override fun route(command: RoutingCommand): RoutingResult {
        commands += command
        return RoutingResult.ACCEPTED
    }

    override fun close() {}
}
