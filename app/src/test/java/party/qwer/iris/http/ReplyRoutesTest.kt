package party.qwer.iris.http

import party.qwer.iris.model.ReplyType
import party.qwer.iris.validateReplyMarkdownThreadMetadata
import party.qwer.iris.validateReplyThreadScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ReplyRoutesTest {
    // -- validateReplyThreadScope --

    @Test
    fun `validateReplyThreadScope returns null when no thread metadata`() {
        val result = validateReplyThreadScope(ReplyType.TEXT, threadId = null, threadScope = null)
        assertNull(result)
    }

    @Test
    fun `validateReplyThreadScope rejects zero scope`() {
        val ex =
            assertFailsWith<IllegalArgumentException> {
                validateReplyThreadScope(ReplyType.TEXT, threadId = 1L, threadScope = 0)
            }
        assertEquals("threadScope must be a positive integer", ex.message)
    }

    @Test
    fun `validateReplyThreadScope rejects scope without threadId`() {
        val ex =
            assertFailsWith<IllegalArgumentException> {
                validateReplyThreadScope(ReplyType.TEXT, threadId = null, threadScope = 3)
            }
        assertEquals("threadScope requires threadId unless scope is 1", ex.message)
    }

    @Test
    fun `validateReplyThreadScope accepts scope 1 without threadId`() {
        val result = validateReplyThreadScope(ReplyType.TEXT, threadId = null, threadScope = 1)
        assertEquals(1, result)
    }

    @Test
    fun `validateReplyThreadScope accepts valid scope with threadId`() {
        val result = validateReplyThreadScope(ReplyType.TEXT, threadId = 100L, threadScope = 2)
        assertEquals(2, result)
    }

    // -- validateReplyMarkdownThreadMetadata --

    @Test
    fun `validateReplyMarkdownThreadMetadata returns null when no metadata`() {
        val result = validateReplyMarkdownThreadMetadata(threadId = null, threadScope = null)
        assertNull(result)
    }

    @Test
    fun `validateReplyMarkdownThreadMetadata defaults scope to 2`() {
        val result = validateReplyMarkdownThreadMetadata(threadId = 123L, threadScope = null)
        assertEquals(2, result)
    }

    @Test
    fun `validateReplyMarkdownThreadMetadata rejects threadScope without threadId`() {
        assertFailsWith<IllegalArgumentException> {
            validateReplyMarkdownThreadMetadata(threadId = null, threadScope = 2)
        }
    }

    @Test
    fun `validateReplyMarkdownThreadMetadata accepts valid metadata`() {
        val result = validateReplyMarkdownThreadMetadata(threadId = 123L, threadScope = 3)
        assertEquals(3, result)
    }

    @Test
    fun `validateReplyMarkdownThreadMetadata rejects zero scope`() {
        assertFailsWith<IllegalArgumentException> {
            validateReplyMarkdownThreadMetadata(threadId = 123L, threadScope = 0)
        }
    }
}
