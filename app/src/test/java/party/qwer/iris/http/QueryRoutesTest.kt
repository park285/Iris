package party.qwer.iris.http

import party.qwer.iris.ApiRequestException
import party.qwer.iris.requireQueryText
import party.qwer.iris.requireReadOnlyQuery
import kotlin.test.Test
import kotlin.test.assertFailsWith

class QueryRoutesTest {
    // -- requireQueryText --

    @Test
    fun `requireQueryText rejects blank query`() {
        assertFailsWith<ApiRequestException> {
            requireQueryText("   ")
        }
    }

    @Test
    fun `requireQueryText accepts non-blank query`() {
        requireQueryText("SELECT 1")
    }

    // -- requireReadOnlyQuery --

    @Test
    fun `requireReadOnlyQuery rejects DELETE`() {
        assertFailsWith<ApiRequestException> {
            requireReadOnlyQuery("DELETE FROM chat_logs WHERE 1=1")
        }
    }

    @Test
    fun `requireReadOnlyQuery accepts SELECT`() {
        requireReadOnlyQuery("SELECT * FROM chat_logs LIMIT 10")
    }

    @Test
    fun `requireReadOnlyQuery accepts WITH SELECT`() {
        requireReadOnlyQuery("WITH cte AS (SELECT 1) SELECT * FROM cte")
    }
}
