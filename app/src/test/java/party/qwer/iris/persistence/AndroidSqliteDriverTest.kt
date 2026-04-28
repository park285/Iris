package party.qwer.iris.persistence

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidSqliteDriverTest {
    @Test
    fun `pragma statements use rawQuery execution path`() {
        assertTrue(shouldExecuteViaRawQueryForTest("PRAGMA journal_mode=WAL"))
        assertTrue(shouldExecuteViaRawQueryForTest(" pragma busy_timeout=5000"))
    }

    @Test
    fun `non pragma statements keep execSQL execution path`() {
        assertFalse(shouldExecuteViaRawQueryForTest("CREATE TABLE test(id INTEGER PRIMARY KEY)"))
        assertFalse(shouldExecuteViaRawQueryForTest("INSERT INTO test(id) VALUES (1)"))
    }

    @Test
    fun `executeBoundUpdate closes statement after successful update`() {
        val statement = FakeBindableSqliteStatement(updateResult = 3)

        val updated =
            executeBoundUpdate(
                statementFactory = { sql ->
                    assertEquals("UPDATE test SET value = ? WHERE id = ?", sql)
                    statement
                },
                sql = "UPDATE test SET value = ? WHERE id = ?",
                args = listOf("value", 1L),
            )

        assertEquals(3, updated)
        assertTrue(statement.closed)
        assertEquals(listOf("string:1:value", "long:2:1"), statement.bindEvents)
    }

    @Test
    fun `executeBoundUpdate closes statement when execution fails`() {
        val statement = FakeBindableSqliteStatement(executionError = IllegalStateException("boom"))

        val error =
            assertFailsWith<IllegalStateException> {
                executeBoundUpdate(
                    statementFactory = { statement },
                    sql = "UPDATE test SET value = ?",
                    args = listOf(1),
                )
            }

        assertEquals("boom", error.message)
        assertTrue(statement.closed)
    }
}

private class FakeBindableSqliteStatement(
    private val updateResult: Int = 0,
    private val executionError: Throwable? = null,
) : BindableSqliteStatement {
    val bindEvents = mutableListOf<String>()
    var closed = false

    override fun bindNull(index: Int) {
        bindEvents += "null:$index"
    }

    override fun bindLong(
        index: Int,
        value: Long,
    ) {
        bindEvents += "long:$index:$value"
    }

    override fun bindString(
        index: Int,
        value: String,
    ) {
        bindEvents += "string:$index:$value"
    }

    override fun executeUpdateDelete(): Int {
        executionError?.let { throw it }
        return updateResult
    }

    override fun close() {
        closed = true
    }
}
