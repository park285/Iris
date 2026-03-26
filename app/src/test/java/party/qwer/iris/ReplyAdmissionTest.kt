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
    private val threadReady =
        GraftReadinessChecker {
            GraftReadinessSnapshot(
                ready = true,
                state = GraftDaemonState.READY,
                checkedAtMs = 1711280000L,
            )
        }

    private val threadBlocked =
        GraftReadinessChecker {
            GraftReadinessSnapshot(
                ready = false,
                state = GraftDaemonState.BLOCKED,
                checkedAtMs = 1711280000L,
                detail = "hook targets missing",
            )
        }

    @Test
    fun `maps reply admission statuses to expected http codes`() {
        assertEquals(HttpStatusCode.Accepted, replyAdmissionHttpStatus(ReplyAdmissionStatus.ACCEPTED))
        assertEquals(HttpStatusCode.TooManyRequests, replyAdmissionHttpStatus(ReplyAdmissionStatus.QUEUE_FULL))
        assertEquals(HttpStatusCode.ServiceUnavailable, replyAdmissionHttpStatus(ReplyAdmissionStatus.SHUTDOWN))
        assertEquals(HttpStatusCode.BadRequest, replyAdmissionHttpStatus(ReplyAdmissionStatus.INVALID_PAYLOAD))
        assertEquals(HttpStatusCode.ServiceUnavailable, replyAdmissionHttpStatus(ReplyAdmissionStatus.GRAFT_NOT_READY))
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
    fun `rejects threaded image replies when graft daemon is not ready`() {
        val sender = RecordingMessageSender()

        val result =
            admitReply(
                replyRequest =
                    ReplyRequest(
                        type = ReplyType.IMAGE,
                        room = "123",
                        data = JsonPrimitive("base64"),
                        threadId = "456",
                        threadScope = 2,
                    ),
                roomId = 123L,
                notificationReferer = "Iris",
                threadId = 456L,
                threadScope = 2,
                messageSender = sender,
                graftReadinessChecker = threadBlocked,
            )

        assertEquals(ReplyAdmissionStatus.GRAFT_NOT_READY, result.status)
        assertEquals(0, sender.photoCalls)
    }

    @Test
    fun `allows plain image replies even when graft daemon is blocked`() {
        val sender = RecordingMessageSender()

        val result =
            admitReply(
                replyRequest =
                    ReplyRequest(
                        type = ReplyType.IMAGE,
                        room = "123",
                        data = JsonPrimitive("base64"),
                    ),
                roomId = 123L,
                notificationReferer = "Iris",
                threadId = null,
                threadScope = null,
                messageSender = sender,
                graftReadinessChecker = threadBlocked,
            )

        assertEquals(ReplyAdmissionStatus.ACCEPTED, result.status)
        assertEquals(1, sender.photoCalls)
    }

    @Test
    fun `allows threaded image replies when graft daemon is ready`() {
        val sender = RecordingMessageSender()

        val result =
            admitReply(
                replyRequest =
                    ReplyRequest(
                        type = ReplyType.IMAGE_MULTIPLE,
                        room = "123",
                        data =
                            kotlinx.serialization.json.buildJsonArray {
                                add(JsonPrimitive("a"))
                                add(JsonPrimitive("b"))
                            },
                        threadId = "456",
                        threadScope = 2,
                    ),
                roomId = 123L,
                notificationReferer = "Iris",
                threadId = 456L,
                threadScope = 2,
                messageSender = sender,
                graftReadinessChecker = threadReady,
            )

        assertEquals(ReplyAdmissionStatus.ACCEPTED, result.status)
        assertEquals(1, sender.multiPhotoCalls)
    }
}

private class RecordingMessageSender : MessageSender {
    var photoCalls = 0
    var multiPhotoCalls = 0

    override fun sendMessage(
        referer: String,
        chatId: Long,
        msg: String,
        threadId: Long?,
        threadScope: Int?,
    ): ReplyAdmissionResult = ReplyAdmissionResult(ReplyAdmissionStatus.ACCEPTED)

    override fun sendPhoto(
        room: Long,
        base64ImageDataString: String,
        threadId: Long?,
        threadScope: Int?,
    ): ReplyAdmissionResult {
        photoCalls += 1
        return ReplyAdmissionResult(ReplyAdmissionStatus.ACCEPTED)
    }

    override fun sendMultiplePhotos(
        room: Long,
        base64ImageDataStrings: List<String>,
        threadId: Long?,
        threadScope: Int?,
    ): ReplyAdmissionResult {
        multiPhotoCalls += 1
        return ReplyAdmissionResult(ReplyAdmissionStatus.ACCEPTED)
    }

    override fun sendNativePhoto(
        room: Long,
        base64ImageDataString: String,
        threadId: Long?,
        threadScope: Int?,
    ): ReplyAdmissionResult = ReplyAdmissionResult(ReplyAdmissionStatus.ACCEPTED)

    override fun sendNativeMultiplePhotos(
        room: Long,
        base64ImageDataStrings: List<String>,
        threadId: Long?,
        threadScope: Int?,
    ): ReplyAdmissionResult = ReplyAdmissionResult(ReplyAdmissionStatus.ACCEPTED)

    override fun sendTextShare(
        room: Long,
        msg: String,
    ): ReplyAdmissionResult = ReplyAdmissionResult(ReplyAdmissionStatus.ACCEPTED)

    override fun sendReplyMarkdown(
        room: Long,
        msg: String,
    ): ReplyAdmissionResult = ReplyAdmissionResult(ReplyAdmissionStatus.ACCEPTED)
}
