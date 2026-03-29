package party.qwer.iris

import kotlinx.serialization.json.JsonPrimitive
import party.qwer.iris.model.QueryColumn
import party.qwer.iris.storage.ChatId
import party.qwer.iris.storage.KakaoDbSqlClient
import party.qwer.iris.storage.ThreadQueries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 테스트용 QueryExecutionResult 빌더
private fun stubResult(
    columns: List<String>,
    rows: List<Map<String, String?>>,
): QueryExecutionResult {
    val cols = columns.map { QueryColumn(name = it, sqliteType = "TEXT") }
    val jsonRows =
        rows.map { row ->
            columns.map { col ->
                row[col]?.let { JsonPrimitive(it) }
            }
        }
    return QueryExecutionResult(cols, jsonRows)
}

private fun emptyResult(): QueryExecutionResult = QueryExecutionResult(emptyList(), emptyList())

private fun buildThreadQueries(
    executeQueryTyped: (String, Array<String?>?, Int) -> QueryExecutionResult,
): ThreadQueries {
    val sqlClient = KakaoDbSqlClient(executeQueryTyped)
    return ThreadQueries(sqlClient)
}

class ThreadQueriesTest {
    @Test
    fun `listThreads returns empty list when no rows`() {
        val queries =
            buildThreadQueries { _, _, _ -> emptyResult() }
        val result = queries.listThreads(ChatId(12345L))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `listThreads maps all fields correctly`() {
        val queries =
            buildThreadQueries { _, _, _ ->
                stubResult(
                    columns =
                        listOf(
                            "thread_id",
                            "message_count",
                            "last_active_at",
                            "origin_message",
                            "origin_user_id",
                            "origin_v",
                        ),
                    rows =
                        listOf(
                            mapOf(
                                "thread_id" to "9001",
                                "message_count" to "7",
                                "last_active_at" to "1743200000",
                                "origin_message" to "hello",
                                "origin_user_id" to "42",
                                "origin_v" to """{"enc":1}""",
                            ),
                        ),
                )
            }
        val rows = queries.listThreads(ChatId(12345L))
        assertEquals(1, rows.size)
        val row = rows.first()
        assertEquals(9001L, row.threadId)
        assertEquals(7, row.messageCount)
        assertEquals(1743200000L, row.lastActiveAt)
        assertEquals("hello", row.originMessage)
        assertEquals(42L, row.originUserId)
        assertEquals("""{"enc":1}""", row.originV)
    }

    @Test
    fun `listThreads handles null optional fields`() {
        val queries =
            buildThreadQueries { _, _, _ ->
                stubResult(
                    columns =
                        listOf(
                            "thread_id",
                            "message_count",
                            "last_active_at",
                            "origin_message",
                            "origin_user_id",
                            "origin_v",
                        ),
                    rows =
                        listOf(
                            mapOf(
                                "thread_id" to "100",
                                "message_count" to "1",
                                "last_active_at" to null,
                                "origin_message" to null,
                                "origin_user_id" to null,
                                "origin_v" to null,
                            ),
                        ),
                )
            }
        val rows = queries.listThreads(ChatId(12345L))
        assertEquals(1, rows.size)
        val row = rows.first()
        assertNull(row.lastActiveAt)
        assertNull(row.originMessage)
        assertNull(row.originUserId)
        assertNull(row.originV)
    }

    @Test
    fun `listThreads passes correct chatId bind args`() {
        val capturedArgs = mutableListOf<Array<String?>?>()
        val queries =
            buildThreadQueries { _, args, _ ->
                capturedArgs.add(args)
                emptyResult()
            }
        queries.listThreads(ChatId(999L))
        assertEquals(1, capturedArgs.size)
        val args = capturedArgs.first()
        // SQL에서 chatId를 두 번 바인딩(서브쿼리 + 외부 WHERE) + LIMIT 한 번
        assertEquals("999", args?.get(0))
        assertEquals("999", args?.get(1))
        assertEquals("20", args?.get(2))
    }
}
