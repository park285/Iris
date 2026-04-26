package party.qwer.iris

import kotlinx.serialization.json.JsonPrimitive
import party.qwer.iris.model.QueryColumn
import party.qwer.iris.storage.ChatId
import party.qwer.iris.storage.KakaoDbSqlClient
import party.qwer.iris.storage.ThreadQueries
import party.qwer.iris.storage.UserId
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
        assertEquals(UserId(42L), row.originUserId)
        requireNotNull(row.originMetadata)
        assertEquals(1, row.originMetadata.enc)
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
        assertNull(row.originMetadata)
    }

    @Test
    fun `listThreads passes correct chatId bind args`() {
        var capturedSql: String? = null
        val capturedArgs = mutableListOf<Array<String?>?>()
        val queries =
            buildThreadQueries { sql, args, _ ->
                capturedSql = sql
                capturedArgs.add(args)
                emptyResult()
            }
        queries.listThreads(ChatId(999L))
        assertEquals(1, capturedArgs.size)
        val args = capturedArgs.first()
        assertTrue(capturedSql?.contains("WITH thread_stats AS") == true)
        assertEquals("999", args?.get(0))
        assertEquals("20", args?.get(1))
    }

    @Test
    fun `listRecentMessages maps rows and clamps limit to 300`() {
        var capturedSql: String? = null
        val capturedArgs = mutableListOf<Array<String?>?>()
        val queries =
            buildThreadQueries { sql, args, _ ->
                capturedSql = sql
                capturedArgs.add(args)
                stubResult(
                    columns =
                        listOf(
                            "id",
                            "chat_id",
                            "user_id",
                            "message",
                            "type",
                            "created_at",
                            "thread_id",
                            "v",
                        ),
                    rows =
                        listOf(
                            mapOf(
                                "id" to "123",
                                "chat_id" to "777",
                                "user_id" to "42",
                                "message" to "cipher",
                                "type" to "26",
                                "created_at" to "1743200000",
                                "thread_id" to "9001",
                                "v" to """{"enc":1}""",
                            ),
                        ),
                )
            }

        val rows = queries.listRecentMessages(ChatId(777L), 500)

        assertEquals(1, rows.size)
        assertTrue(capturedSql.orEmpty().contains("ORDER BY created_at DESC"))
        assertTrue(capturedSql.orEmpty().contains("id DESC"))
        val args = capturedArgs.first()
        assertEquals("777", args?.get(0))
        assertEquals("300", args?.get(1))
        val row = rows.first()
        assertEquals(123L, row.id)
        assertEquals(ChatId(777L), row.chatId)
        assertEquals(UserId(42L), row.userId)
        assertEquals("cipher", row.message)
        assertEquals(26, row.type)
        assertEquals(1743200000L, row.createdAt)
        assertEquals(9001L, row.threadId)
        requireNotNull(row.metadata)
        assertEquals(1, row.metadata.enc)
    }

    @Test
    fun `listRecentMessages binds afterId and threadId filters with ascending id order`() {
        var capturedSql: String? = null
        var capturedArgs: Array<String?>? = null
        var capturedMaxRows = 0
        val queries =
            buildThreadQueries { sql, args, maxRows ->
                capturedSql = sql
                capturedArgs = args
                capturedMaxRows = maxRows
                emptyResult()
            }

        queries.listRecentMessages(ChatId(777L), limit = 25, afterId = 100L, threadId = 9001L)

        assertTrue(capturedSql.orEmpty().contains("WHERE chat_id = ?"))
        assertTrue(capturedSql.orEmpty().contains("id > ?"))
        assertTrue(capturedSql.orEmpty().contains("thread_id = ?"))
        assertTrue(capturedSql.orEmpty().contains("ORDER BY id ASC"))
        assertEquals(listOf("777", "100", "9001", "25"), capturedArgs?.toList())
        assertEquals(25, capturedMaxRows)
    }

    @Test
    fun `listRecentMessages binds beforeId filter with descending id order`() {
        var capturedSql: String? = null
        var capturedArgs: Array<String?>? = null
        val queries =
            buildThreadQueries { sql, args, _ ->
                capturedSql = sql
                capturedArgs = args
                emptyResult()
            }

        queries.listRecentMessages(ChatId(777L), limit = 10, beforeId = 200L)

        assertTrue(capturedSql.orEmpty().contains("WHERE chat_id = ?"))
        assertTrue(capturedSql.orEmpty().contains("id < ?"))
        assertTrue(capturedSql.orEmpty().contains("ORDER BY id DESC"))
        assertEquals(listOf("777", "200", "10"), capturedArgs?.toList())
    }
}
