package party.qwer.iris.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ObservedProfileQueriesTest {
    private fun client(handler: (String, List<SqlArg>) -> List<SqlRow>): SqlClient =
        object : SqlClient {
            override fun <T> query(spec: QuerySpec<T>): List<T> {
                val rows = handler(spec.sql, spec.bindArgs)
                return rows.map { spec.mapper(it) }
            }
        }

    private fun row(vararg pairs: Pair<String, String?>): SqlRow {
        val columns = pairs.map { it.first }
        val index = columns.withIndex().associate { (i, name) -> name to i }
        val values = pairs.map { it.second?.let { v ->
            kotlinx.serialization.json.JsonPrimitive(v)
        } }
        return SqlRow(index, values)
    }

    @Test
    fun `resolveProfileByChatId returns hint row`() {
        val queries = ObservedProfileQueries(client { sql, _ ->
            if (sql.contains("observed_profiles")) {
                listOf(row("display_name" to "DisplayName", "room_name" to "RoomName"))
            } else {
                emptyList()
            }
        })

        val hint = queries.resolveProfileByChatId(ChatId(42L))
        assertNotNull(hint)
        assertEquals("DisplayName", hint.displayName)
        assertEquals("RoomName", hint.roomName)
    }

    @Test
    fun `resolveProfileByChatId returns null for missing profile`() {
        val queries = ObservedProfileQueries(client { _, _ -> emptyList() })

        assertNull(queries.resolveProfileByChatId(ChatId(42L)))
    }

    @Test
    fun `resolveProfileByChatId returns null on blank display_name`() {
        val queries = ObservedProfileQueries(client { sql, _ ->
            if (sql.contains("observed_profiles")) {
                listOf(row("display_name" to "  ", "room_name" to "  "))
            } else {
                emptyList()
            }
        })

        val hint = queries.resolveProfileByChatId(ChatId(42L))
        assertNotNull(hint)
        assertNull(hint.displayName)
        assertNull(hint.roomName)
    }

    @Test
    fun `resolveDisplayNamesBatch returns map for given userIds`() {
        val queries = ObservedProfileQueries(client { sql, _ ->
            if (sql.contains("observed_profile_user_links")) {
                listOf(
                    row("user_id" to "1", "display_name" to "Alice"),
                    row("user_id" to "2", "display_name" to "Bob"),
                )
            } else {
                emptyList()
            }
        })

        val result = queries.resolveDisplayNamesBatch(listOf(1L, 2L, 3L), chatId = 42L)
        assertEquals("Alice", result[1L])
        assertEquals("Bob", result[2L])
        assertNull(result[3L])
    }

    @Test
    fun `resolveDisplayNamesBatch without chatId omits chat_id filter`() {
        var capturedSql = ""
        val queries = ObservedProfileQueries(client { sql, _ ->
            capturedSql = sql
            emptyList()
        })

        queries.resolveDisplayNamesBatch(listOf(1L), chatId = null)
        assert(!capturedSql.contains("chat_id"))
    }

    @Test
    fun `resolveDisplayNamesBatch with chatId includes chat_id filter`() {
        var capturedSql = ""
        val queries = ObservedProfileQueries(client { sql, _ ->
            capturedSql = sql
            emptyList()
        })

        queries.resolveDisplayNamesBatch(listOf(1L), chatId = 42L)
        assert(capturedSql.contains("chat_id = ?"))
    }

    @Test
    fun `resolveDisplayNamesBatch keeps first occurrence per userId`() {
        val queries = ObservedProfileQueries(client { sql, _ ->
            if (sql.contains("observed_profile_user_links")) {
                listOf(
                    row("user_id" to "1", "display_name" to "First"),
                    row("user_id" to "1", "display_name" to "Second"),
                )
            } else {
                emptyList()
            }
        })

        val result = queries.resolveDisplayNamesBatch(listOf(1L), chatId = null)
        assertEquals("First", result[1L])
    }

    @Test
    fun `resolveDisplayNamesBatch filters out blank display names`() {
        val queries = ObservedProfileQueries(client { sql, _ ->
            if (sql.contains("observed_profile_user_links")) {
                listOf(row("user_id" to "1", "display_name" to "   "))
            } else {
                emptyList()
            }
        })

        val result = queries.resolveDisplayNamesBatch(listOf(1L), chatId = null)
        assertNull(result[1L])
    }

    @Test
    fun `resolveDisplayNamesBatch returns empty for empty input`() {
        val queries = ObservedProfileQueries(client { _, _ ->
            throw AssertionError("should not query")
        })

        assertEquals(emptyMap(), queries.resolveDisplayNamesBatch(emptyList(), chatId = null))
    }
}
