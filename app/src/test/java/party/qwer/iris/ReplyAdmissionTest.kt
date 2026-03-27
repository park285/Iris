package party.qwer.iris

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonPrimitive
import party.qwer.iris.model.ReplyRequest
import party.qwer.iris.model.ReplyType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    fun `thread replies are supported for text and image messages`() {
        assertTrue(supportsThreadReply(ReplyType.TEXT))
        assertTrue(supportsThreadReply(ReplyType.IMAGE))
        assertTrue(supportsThreadReply(ReplyType.IMAGE_MULTIPLE))
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
    fun `accepts image replies with explicit thread metadata`() {
        assertEquals(1, validateReplyThreadScope(ReplyType.IMAGE, threadId = 123L, threadScope = 1))
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

    @Test
    fun `reply markdown route only allows text replies`() {
        val error =
            assertFailsWith<IllegalArgumentException> {
                validateReplyMarkdownType(ReplyType.IMAGE)
            }

        assertEquals("reply-markdown replies require type=text", error.message)
    }

    @Test
    fun `reply image route only allows image types`() {
        val error =
            assertFailsWith<IllegalArgumentException> {
                validateReplyImageType(ReplyType.TEXT)
            }

        assertEquals("reply-image replies require type=image or image_multiple", error.message)
    }

    @Test
    fun `reply image route accepts room image without thread metadata`() {
        assertEquals(null, validateReplyImageThreadScope(threadId = null, threadScope = null))
    }

    @Test
    fun `reply image route requires explicit thread scope when thread id exists`() {
        val error =
            assertFailsWith<IllegalArgumentException> {
                validateReplyImageThreadScope(threadId = 123L, threadScope = null)
            }

        assertEquals("reply-image threadId requires threadScope", error.message)
    }

    @Test
    fun `reply image route restricts scope to thread detail variants`() {
        val error =
            assertFailsWith<IllegalArgumentException> {
                validateReplyImageThreadScope(threadId = 123L, threadScope = 1)
            }

        assertEquals("reply-image threadScope must be 2 or 3", error.message)
    }

    @Test
    fun `admit reply forwards request id to message sender`() {
        val sender = RecordingMessageSender()

        val result =
            admitReply(
                replyRequest =
                    ReplyRequest(
                        room = "1",
                        type = ReplyType.TEXT,
                        data = JsonPrimitive("hello"),
                    ),
                roomId = 1L,
                notificationReferer = "Iris",
                threadId = null,
                threadScope = null,
                messageSender = sender,
                requestId = "reply-123",
            )

        assertEquals(ReplyAdmissionStatus.ACCEPTED, result.status)
        assertEquals("reply-123", sender.lastRequestId)
    }
}

private class RecordingMessageSender : MessageSender {
    var photoCalls = 0
    var multiPhotoCalls = 0
    var lastRequestId: String? = null

    override fun sendMessage(
        referer: String,
        chatId: Long,
        msg: String,
        threadId: Long?,
        threadScope: Int?,
        requestId: String?,
    ): ReplyAdmissionResult {
        lastRequestId = requestId
        return ReplyAdmissionResult(ReplyAdmissionStatus.ACCEPTED)
    }

    override fun sendPhoto(
        room: Long,
        base64ImageDataString: String,
        threadId: Long?,
        threadScope: Int?,
        requestId: String?,
    ): ReplyAdmissionResult {
        photoCalls += 1
        lastRequestId = requestId
        return ReplyAdmissionResult(ReplyAdmissionStatus.ACCEPTED)
    }

    override fun sendMultiplePhotos(
        room: Long,
        base64ImageDataStrings: List<String>,
        threadId: Long?,
        threadScope: Int?,
        requestId: String?,
    ): ReplyAdmissionResult {
        multiPhotoCalls += 1
        lastRequestId = requestId
        return ReplyAdmissionResult(ReplyAdmissionStatus.ACCEPTED)
    }

    override fun sendNativePhoto(
        room: Long,
        base64ImageDataString: String,
        threadId: Long?,
        threadScope: Int?,
        requestId: String?,
    ): ReplyAdmissionResult {
        lastRequestId = requestId
        return ReplyAdmissionResult(ReplyAdmissionStatus.ACCEPTED)
    }

    override fun sendNativeMultiplePhotos(
        room: Long,
        base64ImageDataStrings: List<String>,
        threadId: Long?,
        threadScope: Int?,
        requestId: String?,
    ): ReplyAdmissionResult {
        lastRequestId = requestId
        return ReplyAdmissionResult(ReplyAdmissionStatus.ACCEPTED)
    }

    override fun sendTextShare(
        room: Long,
        msg: String,
        requestId: String?,
    ): ReplyAdmissionResult {
        lastRequestId = requestId
        return ReplyAdmissionResult(ReplyAdmissionStatus.ACCEPTED)
    }

    override fun sendReplyMarkdown(
        room: Long,
        msg: String,
        requestId: String?,
    ): ReplyAdmissionResult {
        lastRequestId = requestId
        return ReplyAdmissionResult(ReplyAdmissionStatus.ACCEPTED)
    }
}
