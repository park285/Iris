package party.qwer.iris

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QuerySanitizerTest {
    @Test
    fun `SELECT is allowed`() {
        assertTrue(isReadOnlyQuery("SELECT * FROM chat_logs"))
    }

    @Test
    fun `SELECT with leading whitespace is allowed`() {
        assertTrue(isReadOnlyQuery("  SELECT 1"))
    }

    @Test
    fun `WITH SELECT CTE is allowed`() {
        assertTrue(isReadOnlyQuery("WITH cte AS (SELECT 1) SELECT * FROM cte"))
    }

    @Test
    fun `safe PRAGMA table_info is allowed`() {
        assertTrue(isReadOnlyQuery("PRAGMA table_info(chat_logs)"))
    }

    @Test
    fun `safe PRAGMA index_list is allowed`() {
        assertTrue(isReadOnlyQuery("PRAGMA index_list(chat_logs)"))
    }

    @Test
    fun `PRAGMA compile_options is allowed`() {
        assertTrue(isReadOnlyQuery("PRAGMA compile_options"))
    }

    @Test
    fun `PRAGMA database_list is rejected`() {
        assertFalse(isReadOnlyQuery("PRAGMA database_list"))
    }

    @Test
    fun `PRAGMA encoding assignment is rejected`() {
        assertFalse(isReadOnlyQuery("PRAGMA encoding = 'UTF-16'"))
    }

    @Test
    fun `PRAGMA max_page_count assignment is rejected`() {
        assertFalse(isReadOnlyQuery("PRAGMA max_page_count = 1"))
    }

    @Test
    fun `dangerous PRAGMA writable_schema is rejected`() {
        assertFalse(isReadOnlyQuery("PRAGMA writable_schema = ON"))
    }

    @Test
    fun `dangerous PRAGMA journal_mode is rejected`() {
        assertFalse(isReadOnlyQuery("PRAGMA journal_mode = DELETE"))
    }

    @Test
    fun `WITH DELETE CTE is rejected`() {
        assertFalse(isReadOnlyQuery("WITH x AS (DELETE FROM chat_logs RETURNING *) SELECT * FROM x"))
    }

    @Test
    fun `WITH UPDATE CTE is rejected`() {
        assertFalse(isReadOnlyQuery("WITH x AS (UPDATE chat_logs SET message='' RETURNING *) SELECT * FROM x"))
    }

    @Test
    fun `WITH INSERT CTE is rejected`() {
        assertFalse(isReadOnlyQuery("WITH x AS (INSERT INTO t VALUES(1) RETURNING *) SELECT * FROM x"))
    }

    @Test
    fun `DROP TABLE is rejected`() {
        assertFalse(isReadOnlyQuery("DROP TABLE chat_logs"))
    }

    @Test
    fun `INSERT is rejected`() {
        assertFalse(isReadOnlyQuery("INSERT INTO chat_logs VALUES (1)"))
    }

    @Test
    fun `ATTACH DATABASE is rejected`() {
        assertFalse(isReadOnlyQuery("ATTACH DATABASE '/tmp/evil.db' AS evil"))
    }

    @Test
    fun `DETACH DATABASE is rejected`() {
        assertFalse(isReadOnlyQuery("DETACH DATABASE db2"))
    }

    @Test
    fun `empty query is rejected`() {
        assertFalse(isReadOnlyQuery(""))
        assertFalse(isReadOnlyQuery("   "))
    }

    @Test
    fun `case insensitive rejection of write keywords in WITH`() {
        assertFalse(isReadOnlyQuery("with x as (delete from t returning *) select * from x"))
    }

    @Test
    fun `multiple SELECT statements are rejected`() {
        assertFalse(isReadOnlyQuery("SELECT 1; SELECT 2"))
    }

    @Test
    fun `PRAGMA followed by second statement is rejected`() {
        assertFalse(isReadOnlyQuery("PRAGMA table_info(chat_logs); DROP TABLE chat_logs"))
    }

    @Test
    fun `SELECT containing column named delete is allowed`() {
        assertTrue(isReadOnlyQuery("SELECT delete_flag FROM chat_logs"))
    }

    @Test
    fun `SELECT with REPLACE function is allowed`() {
        assertTrue(isReadOnlyQuery("SELECT REPLACE(name, 'old', 'new') FROM friends"))
    }

    @Test
    fun `SELECT with string literal containing DELETE is rejected as false positive`() {
        // regex 기반 검증의 한계: 문자열 리터럴 내 키워드도 차단됨
        // 보안상 안전한 방향(과잉 차단)이므로 현재 동작을 문서화
        assertFalse(isReadOnlyQuery("SELECT * FROM t WHERE msg = 'DELETE ME'"))
    }

    @Test
    fun `standalone REPLACE statement is rejected via INSERT`() {
        assertFalse(isReadOnlyQuery("INSERT OR REPLACE INTO t VALUES(1)"))
    }
}
