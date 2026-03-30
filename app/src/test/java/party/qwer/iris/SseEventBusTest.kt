package party.qwer.iris

import kotlinx.coroutines.DelicateCoroutinesApi
import party.qwer.iris.http.SseEventEnvelope
import party.qwer.iris.http.initialSseFrames
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(DelicateCoroutinesApi::class)
class SseEventBusTest {
    @Test
    fun `initialSseFrames includes event type field`() {
        val envelope = SseEventEnvelope(id = 1, eventType = "snapshot", payload = "{}", createdAtMs = 0)

        val frame = initialSseFrames(listOf(envelope))

        assertTrue(frame.contains("event: snapshot"))
        assertTrue(frame.contains("id: 1"))
        assertTrue(frame.contains("data: {}"))
    }

    @Test
    fun `stores events in ring buffer`() {
        val bus = SseEventBus(bufferSize = 3)
        bus.emit("event-1")
        bus.emit("event-2")
        bus.emit("event-3")
        bus.emit("event-4")
        assertEquals(3, bus.replayFrom(0).size)
        assertEquals("event-2", bus.replayFrom(0)[0].second)
    }

    @Test
    fun `replayFrom returns events after given id`() {
        val bus = SseEventBus(bufferSize = 10)
        bus.emit("a")
        bus.emit("b")
        bus.emit("c")
        val replay = bus.replayFrom(1)
        assertEquals(2, replay.size)
        assertEquals("b", replay[0].second)
        assertEquals("c", replay[1].second)
    }

    @Test
    fun `replayFrom returns empty when no events after id`() {
        val bus = SseEventBus(bufferSize = 10)
        bus.emit("a")
        val replay = bus.replayFrom(1)
        assertTrue(replay.isEmpty())
    }

    @Test
    fun `monotonic ids increment`() {
        val bus = SseEventBus(bufferSize = 10)
        bus.emit("a")
        bus.emit("b")
        val all = bus.replayFrom(0)
        assertEquals(1L, all[0].first)
        assertEquals(2L, all[1].first)
    }

    @Test
    fun `close closes open subscriber channels`() {
        val bus = SseEventBus(bufferSize = 10)
        val channel = bus.openSubscriberChannel()

        bus.close()

        assertTrue(channel.isClosedForSend)
        assertEquals(0, bus.subscriberCount())
    }

    @Test
    fun `listeners receive typed event envelopes`() {
        val bus = SseEventBus(bufferSize = 10)
        val received = mutableListOf<SseEventEnvelope>()
        val listener: (SseEventEnvelope) -> Unit = { envelope -> received += envelope }

        val listenerId = bus.addListener(listener)
        bus.emit("{}", "snapshot")
        bus.removeListener(listenerId)

        assertEquals(1, received.size)
        assertEquals("snapshot", received.single().eventType)
        assertEquals("{}", received.single().payload)
    }
}
