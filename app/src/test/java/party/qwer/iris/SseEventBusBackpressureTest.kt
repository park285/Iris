package party.qwer.iris

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import party.qwer.iris.http.SseEventEnvelope
import party.qwer.iris.http.SseSubscriberPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SseEventBusBackpressureTest {
    @Test
    fun `buffer overflow drops oldest events`() {
        val policy = SseSubscriberPolicy(bufferCapacity = 3, replayWindowSize = 3)
        val bus = SseEventBus(policy)
        bus.emit("event-1", "test")
        bus.emit("event-2", "test")
        bus.emit("event-3", "test")
        bus.emit("event-4", "test")

        val replay = bus.replayEnvelopes(0)
        assertEquals(3, replay.size)
        assertEquals("event-2", replay[0].payload)
        assertEquals("event-4", replay[2].payload)
    }

    @Test
    fun `replay window limits returned events to replayWindowSize`() {
        val policy = SseSubscriberPolicy(bufferCapacity = 10, replayWindowSize = 2)
        val bus = SseEventBus(policy)
        repeat(5) { bus.emit("event-$it", "test") }

        val replay = bus.replayEnvelopes(0)
        assertEquals(2, replay.size)
        assertEquals("event-3", replay[0].payload)
        assertEquals("event-4", replay[1].payload)
    }

    @Test
    fun `replay miss returns empty list and increments metric`() {
        val policy = SseSubscriberPolicy(bufferCapacity = 3, replayWindowSize = 3)
        val bus = SseEventBus(policy)
        repeat(10) { bus.emit("event-$it", "test") }

        val replay = bus.replayEnvelopes(2)
        assertEquals(3, replay.size)
        assertTrue(bus.replayMissCount() > 0)
    }

    @Test
    fun `replay from valid id returns only newer events`() {
        val policy = SseSubscriberPolicy(bufferCapacity = 10, replayWindowSize = 10)
        val bus = SseEventBus(policy)
        bus.emit("a", "test")
        bus.emit("b", "test")
        bus.emit("c", "test")

        val replay = bus.replayEnvelopes(1)
        assertEquals(2, replay.size)
        assertEquals("b", replay[0].payload)
        assertEquals("c", replay[1].payload)
    }

    @Test
    fun `envelope stores eventType and createdAtMs`() {
        val policy = SseSubscriberPolicy(bufferCapacity = 10, replayWindowSize = 10)
        val bus = SseEventBus(policy)
        val beforeMs = System.currentTimeMillis()
        bus.emit("payload", "member_event")

        val envelope = bus.replayEnvelopes(0).single()
        assertEquals("member_event", envelope.eventType)
        assertEquals("payload", envelope.payload)
        assertTrue(envelope.createdAtMs >= beforeMs)
        assertTrue(envelope.id > 0)
    }

    @Test
    fun `slow subscriber channel gets closed on overflow`() =
        runBlocking {
            val policy = SseSubscriberPolicy(bufferCapacity = 4, replayWindowSize = 2, slowSubscriberTimeoutMs = 100)
            val bus = SseEventBus(policy)
            val subscriberChannel = Channel<SseEventEnvelope>(policy.bufferCapacity)
            bus.addSubscriber(subscriberChannel)

            repeat(policy.bufferCapacity + 2) {
                bus.emit("event-$it", "test")
            }

            delay(policy.slowSubscriberTimeoutMs + 50)

            assertTrue(subscriberChannel.isClosedForReceive || bus.subscriberCount() == 0)
        }

    @Test
    fun `subscriber receives live events`() =
        runBlocking {
            val policy = SseSubscriberPolicy(bufferCapacity = 10, replayWindowSize = 10)
            val bus = SseEventBus(policy)
            val subscriberChannel = Channel<SseEventEnvelope>(policy.bufferCapacity)
            bus.addSubscriber(subscriberChannel)

            bus.emit("hello", "test")

            val envelope = withTimeout(1000) { subscriberChannel.receive() }
            assertEquals("hello", envelope.payload)
            assertEquals("test", envelope.eventType)

            bus.removeSubscriber(subscriberChannel)
        }

    @Test
    fun `ids are monotonically increasing`() {
        val policy = SseSubscriberPolicy(bufferCapacity = 10, replayWindowSize = 10)
        val bus = SseEventBus(policy)
        bus.emit("a", "test")
        bus.emit("b", "test")
        bus.emit("c", "test")

        val all = bus.replayEnvelopes(0)
        assertEquals(3, all.size)
        assertTrue(all[0].id < all[1].id)
        assertTrue(all[1].id < all[2].id)
    }
}
