package party.qwer.iris.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
        val values =
            pairs.map {
                it.second?.let { v ->
                    kotlinx.serialization.json.JsonPrimitive(v)
                }
            }
        return SqlRow(index, values)
    }

    @Test
    fun `resolveProfileByChatId returns hint row`() {
        val queries =
            ObservedProfileQueries(
                client { sql, _ ->
                    if (sql.contains("observed_profiles")) {
                        listOf(row("display_name" to "DisplayName", "room_name" to "RoomName"))
                    } else {
                        emptyList()
                    }
                },
            )

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
        val queries =
            ObservedProfileQueries(
                client { sql, _ ->
                    if (sql.contains("observed_profiles")) {
                        listOf(row("display_name" to "  ", "room_name" to "  "))
                    } else {
                        emptyList()
                    }
                },
            )

        val hint = queries.resolveProfileByChatId(ChatId(42L))
        assertNotNull(hint)
        assertNull(hint.displayName)
        assertNull(hint.roomName)
    }

    @Test
    fun `resolveRoomNamesBatch returns room names for chatIds in one query`() {
        var capturedSql = ""
        var capturedArgs = emptyList<SqlArg>()
        val queries =
            ObservedProfileQueries(
                client { sql, args ->
                    capturedSql = sql
                    capturedArgs = args
                    if (sql.contains("observed_profiles")) {
                        listOf(
                            row("chat_id" to "1", "room_name" to "Alice"),
                            row("chat_id" to "2", "room_name" to "Bob"),
                        )
                    } else {
                        emptyList()
                    }
                },
            )

        val result = queries.resolveRoomNamesBatch(listOf(ChatId(1L), ChatId(2L), ChatId(1L)))

        assertEquals(mapOf(ChatId(1L) to "Alice", ChatId(2L) to "Bob"), result)
        assertTrue(capturedSql.contains("chat_id IN (?,?)"))
        assertTrue(capturedSql.contains("ORDER BY latest.updated_at DESC"))
        assertEquals(listOf(SqlArg.LongVal(1L), SqlArg.LongVal(2L)), capturedArgs)
    }

    @Test
    fun `resolveRoomNamesBatch chunks large chatId lists`() {
        val capturedArgs = mutableListOf<List<SqlArg>>()
        val queries =
            ObservedProfileQueries(
                client { sql, args ->
                    if (sql.contains("chat_id IN")) {
                        capturedArgs += args
                        listOf(row("chat_id" to args.first().valueForTest(), "room_name" to "Room ${args.first().valueForTest()}"))
                    } else {
                        emptyList()
                    }
                },
            )

        val result = queries.resolveRoomNamesBatch((1L..201L).map(::ChatId))

        assertEquals("Room 1", result.getValue(ChatId(1L)))
        assertEquals("Room 201", result.getValue(ChatId(201L)))
        assertEquals(listOf(200, 1), capturedArgs.map { it.size })
    }

    @Test
    fun `resolveRoomNamesBatch falls back to per room lookup when batch query fails`() {
        val singleLookupArgs = mutableListOf<SqlArg>()
        val queries =
            ObservedProfileQueries(
                client { sql, args ->
                    if (sql.contains("chat_id IN")) {
                        error("batch failed")
                    }
                    singleLookupArgs += args
                    listOf(row("display_name" to "Display ${args.single().valueForTest()}", "room_name" to "Room ${args.single().valueForTest()}"))
                },
            )

        val result = queries.resolveRoomNamesBatch(listOf(ChatId(1L), ChatId(2L)))

        assertEquals(mapOf(ChatId(1L) to "Room 1", ChatId(2L) to "Room 2"), result)
        assertEquals(listOf<SqlArg>(SqlArg.LongVal(1L), SqlArg.LongVal(2L)), singleLookupArgs)
    }

    @Test
    fun `resolveRoomNamesBatch returns empty for empty input`() {
        val queries =
            ObservedProfileQueries(
                client { _, _ ->
                    throw AssertionError("should not query")
                },
            )

        assertEquals(emptyMap<ChatId, String>(), queries.resolveRoomNamesBatch(emptyList()))
    }

    @Test
    fun `resolveDisplayNamesBatch returns map for given userIds`() {
        val queries =
            ObservedProfileQueries(
                client { sql, _ ->
                    if (sql.contains("observed_profile_user_links")) {
                        listOf(
                            row("user_id" to "1", "display_name" to "Alice"),
                            row("user_id" to "2", "display_name" to "Bob"),
                        )
                    } else {
                        emptyList()
                    }
                },
            )

        val result = queries.resolveDisplayNamesBatch(listOf(UserId(1L), UserId(2L), UserId(3L)), chatId = ChatId(42L))
        assertEquals("Alice", result[UserId(1L)])
        assertEquals("Bob", result[UserId(2L)])
        assertNull(result[UserId(3L)])
    }

    @Test
    fun `resolveDisplayNamesBatch without chatId omits chat_id filter`() {
        var capturedSql = ""
        val queries =
            ObservedProfileQueries(
                client { sql, _ ->
                    capturedSql = sql
                    emptyList()
                },
            )

        queries.resolveDisplayNamesBatch(listOf(UserId(1L)), chatId = null)
        assert(!capturedSql.contains("chat_id"))
    }

    @Test
    fun `resolveDisplayNamesBatch with chatId includes chat_id filter`() {
        var capturedSql = ""
        val queries =
            ObservedProfileQueries(
                client { sql, _ ->
                    capturedSql = sql
                    emptyList()
                },
            )

        queries.resolveDisplayNamesBatch(listOf(UserId(1L)), chatId = ChatId(42L))
        assert(capturedSql.contains("chat_id = ?"))
    }

    @Test
    fun `resolveDisplayNamesBatch keeps first occurrence per userId`() {
        val queries =
            ObservedProfileQueries(
                client { sql, _ ->
                    if (sql.contains("observed_profile_user_links")) {
                        listOf(
                            row("user_id" to "1", "display_name" to "First"),
                            row("user_id" to "1", "display_name" to "Second"),
                        )
                    } else {
                        emptyList()
                    }
                },
            )

        val result = queries.resolveDisplayNamesBatch(listOf(UserId(1L)), chatId = null)
        assertEquals("First", result[UserId(1L)])
    }

    @Test
    fun `resolveDisplayNamesBatch filters out blank display names`() {
        val queries =
            ObservedProfileQueries(
                client { sql, _ ->
                    if (sql.contains("observed_profile_user_links")) {
                        listOf(row("user_id" to "1", "display_name" to "   "))
                    } else {
                        emptyList()
                    }
                },
            )

        val result = queries.resolveDisplayNamesBatch(listOf(UserId(1L)), chatId = null)
        assertNull(result[UserId(1L)])
    }

    @Test
    fun `resolveDisplayNamesBatch returns empty for empty input`() {
        val queries =
            ObservedProfileQueries(
                client { _, _ ->
                    throw AssertionError("should not query")
                },
            )

        assertEquals(emptyMap<UserId, String>(), queries.resolveDisplayNamesBatch(emptyList(), chatId = null))
    }
}

private fun SqlArg.valueForTest(): String =
    when (this) {
        is SqlArg.Str -> value
        is SqlArg.LongVal -> value.toString()
        is SqlArg.IntVal -> value.toString()
        SqlArg.Null -> ""
    }
