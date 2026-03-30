package party.qwer.iris.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoomStatsQueriesTest {
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
    fun `loadMemberActivity returns activity rows`() {
        val queries =
            RoomStatsQueries(
                client { sql, _ ->
                    if (sql.contains("chat_logs") && sql.contains("GROUP BY")) {
                        listOf(
                            row("user_id" to "1", "message_count" to "10", "last_active" to "1000"),
                            row("user_id" to "2", "message_count" to "5", "last_active" to "900"),
                        )
                    } else {
                        emptyList()
                    }
                },
            )

        val result = queries.loadMemberActivity(ChatId(42L), listOf(UserId(1L), UserId(2L)))
        assertEquals(2, result.size)
        assertEquals(10, result[0].messageCount)
        assertEquals(UserId(1L), result[0].userId)
    }

    @Test
    fun `loadMemberActivity returns empty for empty member list`() {
        val queries =
            RoomStatsQueries(
                client { _, _ ->
                    throw AssertionError("should not query")
                },
            )

        assertTrue(queries.loadMemberActivity(ChatId(42L), emptyList<UserId>()).isEmpty())
    }

    @Test
    fun `loadAllActivity returns activity without member filter`() {
        val queries =
            RoomStatsQueries(
                client { sql, _ ->
                    if (sql.contains("chat_logs") && sql.contains("GROUP BY")) {
                        listOf(
                            row("user_id" to "1", "message_count" to "10", "last_active" to "1000"),
                        )
                    } else {
                        emptyList()
                    }
                },
            )

        val result = queries.loadAllActivity(ChatId(42L))
        assertEquals(1, result.size)
    }

    @Test
    fun `loadTypeCountStats returns MessageTypeCountRow list`() {
        val queries =
            RoomStatsQueries(
                client { sql, _ ->
                    if (sql.contains("GROUP BY user_id, type")) {
                        listOf(
                            row("user_id" to "1", "type" to "0", "cnt" to "10", "last_active" to "1000"),
                            row("user_id" to "1", "type" to "1", "cnt" to "3", "last_active" to "999"),
                        )
                    } else {
                        emptyList()
                    }
                },
            )

        val result = queries.loadTypeCountStats(ChatId(42L), from = 0L)
        assertEquals(2, result.size)
        assertEquals("0", result[0].type)
        assertEquals(10, result[0].count)
    }

    @Test
    fun `loadMessageLog returns log rows`() {
        val queries =
            RoomStatsQueries(
                client { sql, _ ->
                    if (sql.contains("chat_logs") && sql.contains("ORDER BY created_at")) {
                        listOf(
                            row("type" to "0", "created_at" to "1000"),
                            row("type" to "1", "created_at" to "1001"),
                        )
                    } else {
                        emptyList()
                    }
                },
            )

        val result = queries.loadMessageLog(ChatId(42L), UserId(1L), from = 0L)
        assertEquals(2, result.size)
        assertEquals(1000L, result[0].createdAt)
    }
}
