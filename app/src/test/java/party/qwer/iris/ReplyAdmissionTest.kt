package party.qwer.iris

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
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
    fun `thread replies are supported for text image and markdown messages`() {
        assertTrue(supportsThreadReply(ReplyType.TEXT))
        assertTrue(supportsThreadReply(ReplyType.IMAGE))
        assertTrue(supportsThreadReply(ReplyType.IMAGE_MULTIPLE))
        assertTrue(supportsThreadReply(ReplyType.MARKDOWN))
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
    fun `reply markdown route accepts threaded metadata and defaults scope to two`() {
        assertEquals(3, validateReplyMarkdownThreadMetadata(threadId = 123L, threadScope = 3))
        assertEquals(2, validateReplyMarkdownThreadMetadata(threadId = 123L, threadScope = null))
        assertEquals(null, validateReplyMarkdownThreadMetadata(threadId = null, threadScope = null))
    }

    @Test
    fun `admit reply rejects json single image requests`() {
        val sender = RecordingMessageSender()

        val error =
            assertFailsWith<ApiRequestException> {
                runBlocking {
                    admitReply(
                        replyRequest =
                            ReplyRequest(
                                room = "1",
                                type = ReplyType.IMAGE,
                                data = JsonPrimitive("base64"),
                            ),
                        roomId = 1L,
                        notificationReferer = "Iris",
                        threadId = 123L,
                        threadScope = 2,
                        messageSender = sender,
                        requestId = "reply-image-native-1",
                    )
                }
            }

        assertEquals("image types require multipart/form-data", error.message)
        assertEquals(0, sender.nativePhotoCalls)
    }

    @Test
    fun `admit reply rejects json multiple image requests`() {
        val sender = RecordingMessageSender()

        val error =
            assertFailsWith<ApiRequestException> {
                runBlocking {
                    admitReply(
                        replyRequest =
                            ReplyRequest(
                                room = "1",
                                type = ReplyType.IMAGE_MULTIPLE,
                                data = listOf(JsonPrimitive("a"), JsonPrimitive("b")).let { kotlinx.serialization.json.JsonArray(it) },
                            ),
                        roomId = 1L,
                        notificationReferer = "Iris",
                        threadId = 123L,
                        threadScope = 3,
                        messageSender = sender,
                        requestId = "reply-image-native-2",
                    )
                }
            }

        assertEquals("image types require multipart/form-data", error.message)
        assertEquals(0, sender.nativeMultiPhotoCalls)
    }

    @Test
    fun `admit reply routes markdown through reply markdown sender`() {
        val sender = RecordingMessageSender()

        val result =
            runBlocking {
                admitReply(
                    replyRequest =
                        ReplyRequest(
                            room = "1",
                            type = ReplyType.MARKDOWN,
                            data = JsonPrimitive("# Hello"),
                        ),
                    roomId = 1L,
                    notificationReferer = "Iris",
                    threadId = 123L,
                    threadScope = 2,
                    messageSender = sender,
                    requestId = "reply-markdown-1",
                )
            }

        assertEquals(ReplyAdmissionStatus.ACCEPTED, result.status)
        assertEquals(1, sender.markdownCalls)
        assertEquals("reply-markdown-1", sender.lastRequestId)
    }

    @Test
    fun `admit reply forwards request id to message sender`() {
        val sender = RecordingMessageSender()

        val result =
            runBlocking {
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
            }

        assertEquals(ReplyAdmissionStatus.ACCEPTED, result.status)
        assertEquals("reply-123", sender.lastRequestId)
    }
}

private class RecordingMessageSender : MessageSender {
    var nativePhotoCalls = 0
    var nativeMultiPhotoCalls = 0
    var markdownCalls = 0
    var lastRequestId: String? = null

    override suspend fun sendMessageSuspend(
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

    override suspend fun sendNativePhotoBytesSuspend(
        room: Long,
        imageBytes: ByteArray,
        threadId: Long?,
        threadScope: Int?,
        requestId: String?,
    ): ReplyAdmissionResult {
        nativePhotoCalls += 1
        lastRequestId = requestId
        return ReplyAdmissionResult(ReplyAdmissionStatus.ACCEPTED)
    }

    override suspend fun sendNativeMultiplePhotosBytesSuspend(
        room: Long,
        imageBytesList: List<ByteArray>,
        threadId: Long?,
        threadScope: Int?,
        requestId: String?,
    ): ReplyAdmissionResult {
        nativeMultiPhotoCalls += 1
        lastRequestId = requestId
        return ReplyAdmissionResult(ReplyAdmissionStatus.ACCEPTED)
    }

    override suspend fun sendTextShareSuspend(
        room: Long,
        msg: String,
        requestId: String?,
    ): ReplyAdmissionResult {
        lastRequestId = requestId
        return ReplyAdmissionResult(ReplyAdmissionStatus.ACCEPTED)
    }

    override suspend fun sendReplyMarkdownSuspend(
        room: Long,
        msg: String,
        threadId: Long?,
        threadScope: Int?,
        requestId: String?,
    ): ReplyAdmissionResult {
        markdownCalls += 1
        lastRequestId = requestId
        return ReplyAdmissionResult(ReplyAdmissionStatus.ACCEPTED)
    }
}
