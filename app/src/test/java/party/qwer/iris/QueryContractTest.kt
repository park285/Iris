package party.qwer.iris

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QueryContractTest {
    @Test
    fun `allows select queries`() {
        assertTrue(isReadOnlyQuery("SELECT * FROM chat_logs"))
    }

    @Test
    fun `allows with queries`() {
        assertTrue(isReadOnlyQuery("  WITH recent AS (SELECT 1) SELECT * FROM recent"))
    }

    @Test
    fun `allows pragma queries`() {
        assertTrue(isReadOnlyQuery("PRAGMA table_info(chat_logs)"))
    }

    @Test
    fun `rejects write queries`() {
        assertFalse(isReadOnlyQuery("UPDATE chat_logs SET message = 'x'"))
        assertFalse(isReadOnlyQuery("DELETE FROM chat_logs"))
        assertFalse(isReadOnlyQuery("INSERT INTO chat_logs VALUES (1)"))
    }
}
