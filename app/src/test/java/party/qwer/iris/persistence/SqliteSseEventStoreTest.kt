package party.qwer.iris.persistence

import party.qwer.iris.http.SseEventEnvelope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SqliteSseEventStoreTest {
    @Test
    fun `insert stores event and returns auto-increment id`() {
        val (helper, store) = createStore()
        helper.use {
            val id = store.insert("message", """{"text":"hello"}""", 1000L)
            assertEquals(1L, id)

            val rows =
                helper.query(
                    "SELECT id, event_type, payload, created_at FROM ${IrisDatabaseSchema.SSE_EVENTS_TABLE}",
                    emptyList(),
                ) { row ->
                    SseEventEnvelope(
                        id = row.getLong(0),
                        eventType = row.getString(1),
                        payload = row.getString(2),
                        createdAtMs = row.getLong(3),
                    )
                }
            assertEquals(1, rows.size)
            assertEquals("message", rows.single().eventType)
            assertEquals("""{"text":"hello"}""", rows.single().payload)
            assertEquals(1000L, rows.single().createdAtMs)
        }
    }

    @Test
    fun `insert returns monotonically increasing ids`() {
        val (helper, store) = createStore()
        helper.use {
            val id1 = store.insert("message", "a", 1000L)
            val id2 = store.insert("message", "b", 2000L)
            val id3 = store.insert("snapshot", "c", 3000L)
            assertEquals(1L, id1)
            assertEquals(2L, id2)
            assertEquals(3L, id3)
        }
    }

    @Test
    fun `replayAfter returns events after given id`() {
        val (helper, store) = createStore()
        helper.use {
            store.insert("message", "a", 1000L)
            store.insert("message", "b", 2000L)
            store.insert("message", "c", 3000L)

            val replay = store.replayAfter(1L, limit = 100)
            assertEquals(2, replay.size)
            assertEquals(2L, replay[0].id)
            assertEquals("b", replay[0].payload)
            assertEquals(3L, replay[1].id)
            assertEquals("c", replay[1].payload)
        }
    }

    @Test
    fun `replayAfter respects limit parameter`() {
        val (helper, store) = createStore()
        helper.use {
            repeat(10) { i ->
                store.insert("message", "event-$i", (i + 1) * 1000L)
            }

            val replay = store.replayAfter(0L, limit = 3)
            assertEquals(3, replay.size)
            assertEquals(1L, replay[0].id)
            assertEquals(2L, replay[1].id)
            assertEquals(3L, replay[2].id)
        }
    }

    @Test
    fun `replayAfter returns empty when no events after id`() {
        val (helper, store) = createStore()
        helper.use {
            store.insert("message", "a", 1000L)

            val replay = store.replayAfter(1L, limit = 100)
            assertTrue(replay.isEmpty())
        }
    }

    @Test
    fun `replayAfter with id zero returns all events up to limit`() {
        val (helper, store) = createStore()
        helper.use {
            store.insert("message", "a", 1000L)
            store.insert("message", "b", 2000L)

            val replay = store.replayAfter(0L, limit = 100)
            assertEquals(2, replay.size)
        }
    }

    @Test
    fun `replayAfter preserves event type`() {
        val (helper, store) = createStore()
        helper.use {
            store.insert("snapshot", """{"rooms":[]}""", 1000L)
            store.insert("message", """{"text":"hi"}""", 2000L)

            val replay = store.replayAfter(0L, limit = 100)
            assertEquals("snapshot", replay[0].eventType)
            assertEquals("message", replay[1].eventType)
        }
    }

    @Test
    fun `maxId returns 0 for empty table`() {
        val (helper, store) = createStore()
        helper.use {
            assertEquals(0L, store.maxId())
        }
    }

    @Test
    fun `maxId returns highest id after inserts`() {
        val (helper, store) = createStore()
        helper.use {
            store.insert("message", "a", 1000L)
            store.insert("message", "b", 2000L)
            store.insert("message", "c", 3000L)
            assertEquals(3L, store.maxId())
        }
    }

    @Test
    fun `prune keeps only specified number of most recent events`() {
        val (helper, store) = createStore()
        helper.use {
            repeat(5) { i ->
                store.insert("message", "event-$i", (i + 1) * 1000L)
            }
            assertEquals(5L, store.maxId())

            store.prune(keepCount = 2)

            val remaining = store.replayAfter(0L, limit = 100)
            assertEquals(2, remaining.size)
            assertEquals(4L, remaining[0].id)
            assertEquals(5L, remaining[1].id)
            assertEquals("event-3", remaining[0].payload)
            assertEquals("event-4", remaining[1].payload)
        }
    }

    @Test
    fun `prune with keepCount greater than total preserves all`() {
        val (helper, store) = createStore()
        helper.use {
            store.insert("message", "a", 1000L)
            store.insert("message", "b", 2000L)

            store.prune(keepCount = 100)

            val remaining = store.replayAfter(0L, limit = 100)
            assertEquals(2, remaining.size)
        }
    }

    @Test
    fun `prune with zero keepCount fails`() {
        val (helper, store) = createStore()
        helper.use {
            store.insert("message", "a", 1000L)
            store.insert("message", "b", 2000L)

            assertFailsWith<IllegalArgumentException> {
                store.prune(keepCount = 0)
            }
        }
    }

    @Test
    fun `prune with negative keepCount fails`() {
        val (helper, store) = createStore()
        helper.use {
            store.insert("message", "a", 1000L)

            assertFailsWith<IllegalArgumentException> {
                store.prune(keepCount = -1)
            }
        }
    }

    @Test
    fun `maxId still reflects highest after prune`() {
        val (helper, store) = createStore()
        helper.use {
            repeat(5) { i ->
                store.insert("message", "event-$i", (i + 1) * 1000L)
            }

            store.prune(keepCount = 1)

            // maxId는 autoincrement 기반이므로 pruned된 후에도 마지막 id 유지
            assertEquals(5L, store.maxId())
        }
    }

    private fun createStore(): Pair<JdbcSqliteHelper, SqliteSseEventStore> {
        val helper = JdbcSqliteHelper.inMemory()
        IrisDatabaseSchema.createSseEventsTable(helper)
        val store = SqliteSseEventStore(helper)
        return helper to store
    }
}
