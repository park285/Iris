package party.qwer.iris.storage

import kotlinx.serialization.json.JsonPrimitive
import party.qwer.iris.QueryExecutionResult
import party.qwer.iris.model.QueryColumn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KakaoDbSqlClientTest {
    private fun resultOf(
        columns: List<String>,
        vararg rows: List<String?>,
    ): QueryExecutionResult {
        val cols = columns.map { QueryColumn(name = it, sqliteType = "TEXT") }
        val jsonRows = rows.map { row -> row.map { it?.let { v -> JsonPrimitive(v) } } }
        return QueryExecutionResult(cols, jsonRows)
    }

    @Test
    fun `query maps rows through spec mapper`() {
        val result = resultOf(listOf("id", "name"), listOf("1", "Alice"), listOf("2", "Bob"))
        val client = KakaoDbSqlClient { _, _, _ -> result }
        val spec = QuerySpec(sql = "SELECT id, name FROM test", bindArgs = emptyList(), maxRows = 100, mapper = { it.string("name")!! })
        assertEquals(listOf("Alice", "Bob"), client.query(spec))
    }

    @Test
    fun `query passes bind args as string array`() {
        var capturedArgs: Array<String?>? = null
        val client =
            KakaoDbSqlClient { _, args, _ ->
                capturedArgs = args
                QueryExecutionResult(emptyList(), emptyList())
            }
        val spec = QuerySpec(sql = "SELECT 1", bindArgs = listOf(SqlArg.LongVal(42L), SqlArg.Str("hello"), SqlArg.Null), maxRows = 10, mapper = { })
        client.query(spec)
        assertEquals(listOf("42", "hello", null), capturedArgs?.toList())
    }

    @Test
    fun `query passes maxRows to executor`() {
        var capturedMaxRows = 0
        val client =
            KakaoDbSqlClient { _, _, max ->
                capturedMaxRows = max
                QueryExecutionResult(emptyList(), emptyList())
            }
        val spec = QuerySpec(sql = "SELECT 1", bindArgs = emptyList(), maxRows = 500, mapper = { })
        client.query(spec)
        assertEquals(500, capturedMaxRows)
    }

    @Test
    fun `querySingle returns first result`() {
        val result = resultOf(listOf("id"), listOf("1"), listOf("2"))
        val client = KakaoDbSqlClient { _, _, _ -> result }
        val spec = QuerySpec(sql = "SELECT id FROM test", bindArgs = emptyList(), maxRows = 100, mapper = { it.long("id")!! })
        assertEquals(1L, client.querySingle(spec))
    }

    @Test
    fun `querySingle returns null for empty result`() {
        val client = KakaoDbSqlClient { _, _, _ -> QueryExecutionResult(emptyList(), emptyList()) }
        val spec = QuerySpec(sql = "SELECT 1", bindArgs = emptyList(), maxRows = 100, mapper = { it.long("id") })
        assertNull(client.querySingle(spec))
    }

    @Test
    fun `empty bind args passes null array`() {
        var capturedArgs: Array<String?>? = arrayOf("sentinel")
        val client =
            KakaoDbSqlClient { _, args, _ ->
                capturedArgs = args
                QueryExecutionResult(emptyList(), emptyList())
            }
        val spec = QuerySpec(sql = "SELECT 1", bindArgs = emptyList(), maxRows = 10, mapper = { })
        client.query(spec)
        assertNull(capturedArgs)
    }
}
