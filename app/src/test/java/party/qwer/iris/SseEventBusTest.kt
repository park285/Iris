package party.qwer.iris

import kotlinx.coroutines.DelicateCoroutinesApi
import party.qwer.iris.http.SseEventEnvelope
import party.qwer.iris.http.SseSubscriberPolicy
import party.qwer.iris.http.initialSseFrames
import party.qwer.iris.persistence.IrisDatabaseSchema
import party.qwer.iris.persistence.JdbcSqliteHelper
import party.qwer.iris.persistence.SqliteSseEventStore
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

    // --- store 통합 테스트 ---

    @Test
    fun `bus with store persists events to SQLite`() {
        val (helper, store) = createStoreBackend()
        helper.use {
            val bus = SseEventBus(
                policy = SseSubscriberPolicy(bufferCapacity = 100, replayWindowSize = 100),
                store = store,
            )
            bus.emit("event-1")
            bus.emit("event-2", "snapshot")
            bus.close()

            // store에 직접 조회하여 persist 확인
            val persisted = store.replayAfter(0L, limit = 100)
            assertEquals(2, persisted.size)
            assertEquals("event-1", persisted[0].payload)
            assertEquals("message", persisted[0].eventType)
            assertEquals("event-2", persisted[1].payload)
            assertEquals("snapshot", persisted[1].eventType)
        }
    }

    @Test
    fun `bus with store uses store ids instead of in-memory counter`() {
        val (helper, store) = createStoreBackend()
        helper.use {
            val bus = SseEventBus(
                policy = SseSubscriberPolicy(bufferCapacity = 100, replayWindowSize = 100),
                store = store,
            )
            bus.emit("a")
            bus.emit("b")

            val replay = bus.replayFrom(0)
            assertEquals(1L, replay[0].first)
            assertEquals(2L, replay[1].first)
            bus.close()
        }
    }

    @Test
    fun `bus with store replays from store beyond buffer`() {
        val (helper, store) = createStoreBackend()
        helper.use {
            val bus = SseEventBus(
                policy = SseSubscriberPolicy(bufferCapacity = 10, replayWindowSize = 10),
                store = store,
            )
            // 10개 emit — 전부 버퍼와 store 양쪽에 저장
            repeat(10) { i -> bus.emit("event-$i") }
            bus.close()

            // 새 bus (재시작 시뮬레이션) — in-memory 버퍼 비어있지만 store에서 replay
            val bus2 = SseEventBus(
                policy = SseSubscriberPolicy(bufferCapacity = 10, replayWindowSize = 10),
                store = store,
            )
            val replay = bus2.replayEnvelopes(0L)
            assertEquals(10, replay.size)
            assertEquals("event-0", replay[0].payload)
            assertEquals("event-9", replay[9].payload)
            bus2.close()
        }
    }

    @Test
    fun `bus without store loses events beyond buffer capacity`() {
        val bus = SseEventBus(
            policy = SseSubscriberPolicy(bufferCapacity = 2, replayWindowSize = 2),
        )
        bus.emit("a")
        bus.emit("b")
        bus.emit("c")
        bus.emit("d")

        // store 없으면 버퍼 크기만큼만 남음
        val replay = bus.replayEnvelopes(0L)
        assertEquals(2, replay.size)
        assertEquals("c", replay[0].payload)
        assertEquals("d", replay[1].payload)
        bus.close()
    }

    @Test
    fun `bus with store initializes nextEventId from store maxId`() {
        val (helper, store) = createStoreBackend()
        helper.use {
            // 먼저 store에 직접 이벤트 삽입 (이전 서버 세션 시뮬레이션)
            store.insert("message", "old-1", 1000L)
            store.insert("message", "old-2", 2000L)
            store.insert("message", "old-3", 3000L)

            // 새 bus 생성 — store.maxId()=3이므로 다음 id는 4부터
            val bus = SseEventBus(
                policy = SseSubscriberPolicy(bufferCapacity = 100, replayWindowSize = 100),
                store = store,
            )
            bus.emit("new-1")

            val replay = bus.replayEnvelopes(3L)
            assertEquals(1, replay.size)
            assertEquals(4L, replay[0].id)
            assertEquals("new-1", replay[0].payload)
            bus.close()
        }
    }

    @Test
    fun `bus with store survives simulated restart`() {
        val (helper, store) = createStoreBackend()
        helper.use {
            // 1차 세션
            val bus1 = SseEventBus(
                policy = SseSubscriberPolicy(bufferCapacity = 100, replayWindowSize = 100),
                store = store,
            )
            bus1.emit("session1-a")
            bus1.emit("session1-b", "snapshot")
            bus1.close()

            // 2차 세션 (같은 store로 새 bus 생성 — 서버 재시작 시뮬레이션)
            val bus2 = SseEventBus(
                policy = SseSubscriberPolicy(bufferCapacity = 100, replayWindowSize = 100),
                store = store,
            )
            bus2.emit("session2-c")

            // 전체 이벤트가 replay 가능해야 함
            val allEvents = bus2.replayEnvelopes(0L)
            assertEquals(3, allEvents.size)
            assertEquals("session1-a", allEvents[0].payload)
            assertEquals("session1-b", allEvents[1].payload)
            assertEquals("session2-c", allEvents[2].payload)
            assertEquals(1L, allEvents[0].id)
            assertEquals(2L, allEvents[1].id)
            assertEquals(3L, allEvents[2].id)
            bus2.close()
        }
    }

    @Test
    fun `bus with store replay miss count stays zero`() {
        val (helper, store) = createStoreBackend()
        helper.use {
            val bus = SseEventBus(
                policy = SseSubscriberPolicy(bufferCapacity = 10, replayWindowSize = 10),
                store = store,
            )
            bus.emit("a")
            bus.emit("b")
            bus.emit("c")
            bus.emit("d")

            // store가 있으면 replay miss가 발생하지 않음 (store에서 조회하므로)
            assertEquals(0L, bus.replayMissCount())
            bus.close()
        }
    }

    private fun createStoreBackend(): Pair<JdbcSqliteHelper, SqliteSseEventStore> {
        val helper = JdbcSqliteHelper.inMemory()
        IrisDatabaseSchema.createSseEventsTable(helper)
        val store = SqliteSseEventStore(helper)
        return helper to store
    }
}
