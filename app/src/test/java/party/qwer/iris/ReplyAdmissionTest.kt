package party.qwer.iris

import io.ktor.http.HttpStatusCode
import party.qwer.iris.model.ReplyType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReplyAdmissionTest {
    @Test
    fun `maps reply admission statuses to expected http codes`() {
        assertEquals(HttpStatusCode.Accepted, replyAdmissionHttpStatus(ReplyAdmissionStatus.ACCEPTED))
        assertEquals(HttpStatusCode.TooManyRequests, replyAdmissionHttpStatus(ReplyAdmissionStatus.QUEUE_FULL))
        assertEquals(HttpStatusCode.ServiceUnavailable, replyAdmissionHttpStatus(ReplyAdmissionStatus.SHUTDOWN))
        assertEquals(HttpStatusCode.BadRequest, replyAdmissionHttpStatus(ReplyAdmissionStatus.INVALID_PAYLOAD))
    }

    @Test
    fun `thread replies are only supported for text messages`() {
        assertTrue(supportsThreadReply(ReplyType.TEXT))
        assertFalse(supportsThreadReply(ReplyType.IMAGE))
        assertFalse(supportsThreadReply(ReplyType.IMAGE_MULTIPLE))
    }

    @Test
    fun `returns null when thread scope is omitted for text replies`() {
        assertEquals(null, validateReplyThreadScope(ReplyType.TEXT, threadId = 123L, threadScope = null))
        assertEquals(null, validateReplyThreadScope(ReplyType.TEXT, threadId = null, threadScope = null))
    }

    @Test
    fun `allows scope one without thread id for text replies`() {
        assertEquals(1, validateReplyThreadScope(ReplyType.TEXT, threadId = null, threadScope = 1))
    }

    @Test
    fun `rejects non text replies with thread scope`() {
        val error =
            assertFailsWith<IllegalArgumentException> {
                validateReplyThreadScope(ReplyType.IMAGE, threadId = 123L, threadScope = 1)
            }

        assertEquals("threadId is only supported for text replies", error.message)
    }

    @Test
    fun `rejects scope without thread id when scope is greater than one`() {
        val error =
            assertFailsWith<IllegalArgumentException> {
                validateReplyThreadScope(ReplyType.TEXT, threadId = null, threadScope = 3)
            }

        assertEquals("threadScope requires threadId unless scope is 1", error.message)
    }

    @Test
    fun `rejects non positive thread scope`() {
        val error =
            assertFailsWith<IllegalArgumentException> {
                validateReplyThreadScope(ReplyType.TEXT, threadId = 123L, threadScope = -1)
            }

        assertEquals("threadScope must be a positive integer", error.message)
    }
}
