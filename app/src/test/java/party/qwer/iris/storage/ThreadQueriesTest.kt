package party.qwer.iris.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ThreadQueriesTest {
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
                it.second?.let { v -> kotlinx.serialization.json.JsonPrimitive(v) }
            }
        return SqlRow(index, values)
    }

    @Test
    fun `listThreads returns empty when no threads`() {
        val queries = ThreadQueries(client { _, _ -> emptyList() })
        val result = queries.listThreads(ChatId(999L))
        assertEquals(0, result.size)
    }

    @Test
    fun `listThreads maps row with origin`() {
        val queries =
            ThreadQueries(
                client { _, _ ->
                    listOf(
                        row(
                            "thread_id" to "100",
                            "msg_count" to "5",
                            "last_active" to "1774787702",
                            "origin_message" to "원본 메시지",
                            "origin_user_id" to "42",
                            "origin_v" to """{"enc":31}""",
                        ),
                    )
                },
            )
        val result = queries.listThreads(ChatId(1L))
        assertEquals(1, result.size)
        val row = result[0]
        assertEquals(100L, row.threadId)
        assertEquals(5, row.messageCount)
        assertNotNull(row.originMessage)
    }

    @Test
    fun `listThreads handles null origin`() {
        val queries =
            ThreadQueries(
                client { _, _ ->
                    listOf(
                        row(
                            "thread_id" to "200",
                            "msg_count" to "1",
                            "last_active" to "1774787702",
                            "origin_message" to null,
                            "origin_user_id" to null,
                            "origin_v" to null,
                        ),
                    )
                },
            )
        val result = queries.listThreads(ChatId(1L))
        assertEquals(1, result.size)
        assertNull(result[0].originMessage)
    }
}
