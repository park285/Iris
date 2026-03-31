package party.qwer.iris

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class MessageSenderBytesAdapterTest {
    @Test
    fun `bytes adapter rejects unknown binary before handle dispatch`() {
        val sender = RecordingMessageSender()

        val result =
            runBlocking {
                sender.sendNativeMultiplePhotosBytesSuspend(
                    room = 1L,
                    imageBytesList = listOf(byteArrayOf(0x01, 0x02, 0x03)),
                )
            }

        assertEquals(ReplyAdmissionStatus.INVALID_PAYLOAD, result.status)
        assertEquals(0, sender.handleCalls)
    }

    private class RecordingMessageSender : MessageSender {
        var handleCalls: Int = 0

        override suspend fun sendMessageSuspend(
            referer: String,
            chatId: Long,
            msg: String,
            threadId: Long?,
            threadScope: Int?,
            requestId: String?,
        ): ReplyAdmissionResult = ReplyAdmissionResult(ReplyAdmissionStatus.ACCEPTED)

        override suspend fun sendNativeMultiplePhotosHandlesSuspend(
            room: Long,
            imageHandles: List<VerifiedImagePayloadHandle>,
            threadId: Long?,
            threadScope: Int?,
            requestId: String?,
        ): ReplyAdmissionResult {
            handleCalls += 1
            return ReplyAdmissionResult(ReplyAdmissionStatus.ACCEPTED)
        }

        override suspend fun sendTextShareSuspend(
            room: Long,
            msg: String,
            requestId: String?,
        ): ReplyAdmissionResult = ReplyAdmissionResult(ReplyAdmissionStatus.ACCEPTED)

        override suspend fun sendReplyMarkdownSuspend(
            room: Long,
            msg: String,
            threadId: Long?,
            threadScope: Int?,
            requestId: String?,
        ): ReplyAdmissionResult = ReplyAdmissionResult(ReplyAdmissionStatus.ACCEPTED)
    }
}
