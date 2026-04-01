package party.qwer.iris.persistence

import party.qwer.iris.model.RoomEventRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SqliteRoomEventStoreTest {
    @Test
    fun `insert stores event and returns auto-increment id`() {
        val (helper, store) = createStore()
        helper.use {
            val id = store.insert(
                chatId = 100L,
                eventType = "member_event",
                userId = 1L,
                payload = """{"type":"member_event","event":"join","chatId":100,"userId":1}""",
                createdAtMs = 1000L,
            )
            assertEquals(1L, id)

            val rows = helper.query(
                "SELECT id, chat_id, event_type, user_id, payload, created_at FROM ${IrisDatabaseSchema.ROOM_EVENTS_TABLE}",
                emptyList(),
            ) { row ->
                RoomEventRecord(
                    id = row.getLong(0),
                    chatId = row.getLong(1),
                    eventType = row.getString(2),
                    userId = row.getLong(3),
                    payload = row.getString(4),
                    createdAt = row.getLong(5),
                )
            }
            assertEquals(1, rows.size)
            val record = rows.single()
            assertEquals(100L, record.chatId)
            assertEquals("member_event", record.eventType)
            assertEquals(1L, record.userId)
            assertEquals(1000L, record.createdAt)
        }
    }

    @Test
    fun `insert returns monotonically increasing ids`() {
        val (helper, store) = createStore()
        helper.use {
            val id1 = store.insert(100L, "member_event", 1L, "{}", 1000L)
            val id2 = store.insert(100L, "nickname_change", 2L, "{}", 2000L)
            val id3 = store.insert(200L, "role_change", 3L, "{}", 3000L)
            assertEquals(1L, id1)
            assertEquals(2L, id2)
            assertEquals(3L, id3)
        }
    }

    @Test
    fun `listByChatId returns events for specific room only`() {
        val (helper, store) = createStore()
        helper.use {
            store.insert(100L, "member_event", 1L, """{"room":100}""", 1000L)
            store.insert(200L, "member_event", 2L, """{"room":200}""", 2000L)
            store.insert(100L, "nickname_change", 3L, """{"room":100}""", 3000L)

            val room100 = store.listByChatId(chatId = 100L, limit = 100)
            assertEquals(2, room100.size)
            assertEquals(100L, room100[0].chatId)
            assertEquals(100L, room100[1].chatId)

            val room200 = store.listByChatId(chatId = 200L, limit = 100)
            assertEquals(1, room200.size)
            assertEquals(200L, room200[0].chatId)
        }
    }

    @Test
    fun `listByChatId respects limit`() {
        val (helper, store) = createStore()
        helper.use {
            repeat(10) { i ->
                store.insert(100L, "member_event", i.toLong(), "{}", (i + 1) * 1000L)
            }

            val limited = store.listByChatId(chatId = 100L, limit = 3)
            assertEquals(3, limited.size)
            assertEquals(1L, limited[0].id)
            assertEquals(2L, limited[1].id)
            assertEquals(3L, limited[2].id)
        }
    }

    @Test
    fun `listByChatId supports afterId cursor for pagination`() {
        val (helper, store) = createStore()
        helper.use {
            repeat(5) { i ->
                store.insert(100L, "member_event", i.toLong(), "{}", (i + 1) * 1000L)
            }

            val page1 = store.listByChatId(chatId = 100L, limit = 2, afterId = 0)
            assertEquals(2, page1.size)
            assertEquals(1L, page1[0].id)
            assertEquals(2L, page1[1].id)

            val page2 = store.listByChatId(chatId = 100L, limit = 2, afterId = page1.last().id)
            assertEquals(2, page2.size)
            assertEquals(3L, page2[0].id)
            assertEquals(4L, page2[1].id)

            val page3 = store.listByChatId(chatId = 100L, limit = 2, afterId = page2.last().id)
            assertEquals(1, page3.size)
            assertEquals(5L, page3[0].id)
        }
    }

    @Test
    fun `listByChatId returns empty for unknown room`() {
        val (helper, store) = createStore()
        helper.use {
            store.insert(100L, "member_event", 1L, "{}", 1000L)

            val result = store.listByChatId(chatId = 999L, limit = 100)
            assertTrue(result.isEmpty())
        }
    }

    @Test
    fun `listByChatId orders by id ascending`() {
        val (helper, store) = createStore()
        helper.use {
            store.insert(100L, "member_event", 3L, "{}", 3000L)
            store.insert(100L, "member_event", 1L, "{}", 1000L)
            store.insert(100L, "member_event", 2L, "{}", 2000L)

            val events = store.listByChatId(chatId = 100L, limit = 100)
            assertEquals(listOf(1L, 2L, 3L), events.map { it.id })
        }
    }

    @Test
    fun `listByChatId preserves all fields`() {
        val (helper, store) = createStore()
        helper.use {
            val payload = """{"type":"nickname_change","chatId":100,"userId":42,"oldNickname":"A","newNickname":"B"}"""
            store.insert(100L, "nickname_change", 42L, payload, 5000L)

            val record = store.listByChatId(chatId = 100L, limit = 1).single()
            assertEquals(100L, record.chatId)
            assertEquals("nickname_change", record.eventType)
            assertEquals(42L, record.userId)
            assertEquals(payload, record.payload)
            assertEquals(5000L, record.createdAt)
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
            store.insert(100L, "member_event", 1L, "{}", 1000L)
            store.insert(200L, "member_event", 2L, "{}", 2000L)
            store.insert(100L, "member_event", 3L, "{}", 3000L)
            assertEquals(3L, store.maxId())
        }
    }

    @Test
    fun `pruneOlderThan deletes events before cutoff`() {
        val (helper, store) = createStore()
        helper.use {
            store.insert(100L, "member_event", 1L, "{}", 1000L)
            store.insert(100L, "member_event", 2L, "{}", 2000L)
            store.insert(100L, "member_event", 3L, "{}", 5000L)
            store.insert(200L, "member_event", 4L, "{}", 6000L)

            val deleted = store.pruneOlderThan(cutoffMs = 3000L)
            assertEquals(2, deleted)

            val remaining = store.listByChatId(100L, limit = 100)
            assertEquals(1, remaining.size)
            assertEquals(5000L, remaining[0].createdAt)
        }
    }

    @Test
    fun `pruneOlderThan preserves events at or after cutoff`() {
        val (helper, store) = createStore()
        helper.use {
            store.insert(100L, "member_event", 1L, "{}", 3000L)
            store.insert(100L, "member_event", 2L, "{}", 3000L)

            val deleted = store.pruneOlderThan(cutoffMs = 3000L)
            assertEquals(0, deleted)

            assertEquals(2, store.listByChatId(100L, limit = 100).size)
        }
    }

    @Test
    fun `pruneOlderThan returns 0 for empty table`() {
        val (helper, store) = createStore()
        helper.use {
            assertEquals(0, store.pruneOlderThan(cutoffMs = 9999L))
        }
    }

    @Test
    fun `events from multiple rooms coexist independently`() {
        val (helper, store) = createStore()
        helper.use {
            store.insert(100L, "member_event", 1L, """{"room":100,"action":"join"}""", 1000L)
            store.insert(200L, "nickname_change", 2L, """{"room":200,"action":"rename"}""", 2000L)
            store.insert(300L, "role_change", 3L, """{"room":300,"action":"promote"}""", 3000L)
            store.insert(100L, "profile_change", 4L, """{"room":100,"action":"avatar"}""", 4000L)

            assertEquals(2, store.listByChatId(100L, limit = 100).size)
            assertEquals(1, store.listByChatId(200L, limit = 100).size)
            assertEquals(1, store.listByChatId(300L, limit = 100).size)
            assertEquals(4L, store.maxId())
        }
    }

    private fun createStore(): Pair<JdbcSqliteHelper, SqliteRoomEventStore> {
        val helper = JdbcSqliteHelper.inMemory()
        IrisDatabaseSchema.createRoomEventsTable(helper)
        val store = SqliteRoomEventStore(helper)
        return helper to store
    }
}
