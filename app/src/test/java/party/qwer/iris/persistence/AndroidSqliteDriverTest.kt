package party.qwer.iris.persistence

import kotlin.test.Test
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
}
