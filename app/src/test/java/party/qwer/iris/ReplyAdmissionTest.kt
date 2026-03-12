package party.qwer.iris

import io.ktor.http.HttpStatusCode
import party.qwer.iris.model.ReplyType
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
