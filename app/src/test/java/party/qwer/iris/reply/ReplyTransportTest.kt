package party.qwer.iris.reply

import party.qwer.iris.NativeImageReplySender
import party.qwer.iris.PreparedImages
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals

class ReplyTransportTest {
    @Test
    fun `dispatches text reply via notification sender for room messages`() {
        val notificationCalls = AtomicInteger(0)
        val shareCalls = AtomicInteger(0)
        val capturedChatId = AtomicReference<Long>()
        val capturedMessage = AtomicReference<CharSequence>()

        val transport =
            ReplyTransport(
                notificationReplySender = { _, chatId, message, _, _ ->
                    notificationCalls.incrementAndGet()
                    capturedChatId.set(chatId)
                    capturedMessage.set(message)
                },
                sharedTextReplySender = { _, _, _, _ -> shareCalls.incrementAndGet() },
                nativeImageReplySender = StubNativeImageSender(),
                mediaPreparationService = null,
            )

        val cmd =
            TextReplyCommand(
                chatId = 100L,
                referer = "Iris",
                message = "hello",
                threadId = null,
                threadScope = null,
                requestId = null,
            )

        transport.sendText(cmd)

        assertEquals(1, notificationCalls.get())
        assertEquals(0, shareCalls.get())
        assertEquals(100L, capturedChatId.get())
        assertEquals("hello", capturedMessage.get().toString())
    }

    @Test
    fun `dispatches threaded text reply via share sender`() {
        val notificationCalls = AtomicInteger(0)
        val shareCalls = AtomicInteger(0)
        val capturedThreadId = AtomicReference<Long?>()
        val capturedScope = AtomicReference<Int?>()

        val transport =
            ReplyTransport(
                notificationReplySender = { _, _, _, _, _ -> notificationCalls.incrementAndGet() },
                sharedTextReplySender = { _, _, threadId, threadScope ->
                    shareCalls.incrementAndGet()
                    capturedThreadId.set(threadId)
                    capturedScope.set(threadScope)
                },
                nativeImageReplySender = StubNativeImageSender(),
                mediaPreparationService = null,
            )

        val cmd =
            TextReplyCommand(
                chatId = 100L,
                referer = "Iris",
                message = "threaded text",
                threadId = 500L,
                threadScope = 2,
                requestId = null,
            )

        transport.sendText(cmd)

        assertEquals(0, notificationCalls.get())
        assertEquals(1, shareCalls.get())
        assertEquals(500L, capturedThreadId.get())
        assertEquals(2, capturedScope.get())
    }

    @Test
    fun `dispatches threaded text reply defaults scope to two`() {
        val capturedScope = AtomicReference<Int?>()

        val transport =
            ReplyTransport(
                notificationReplySender = { _, _, _, _, _ -> },
                sharedTextReplySender = { _, _, _, threadScope -> capturedScope.set(threadScope) },
                nativeImageReplySender = StubNativeImageSender(),
                mediaPreparationService = null,
            )

        val cmd =
            TextReplyCommand(
                chatId = 100L,
                referer = "Iris",
                message = "default scope",
                threadId = 500L,
                threadScope = null,
                requestId = null,
            )

        transport.sendText(cmd)
        assertEquals(2, capturedScope.get())
    }

    @Test
    fun `dispatches share reply via share sender`() {
        val shareCalls = AtomicInteger(0)
        val capturedMessage = AtomicReference<CharSequence>()

        val transport =
            ReplyTransport(
                notificationReplySender = { _, _, _, _, _ -> },
                sharedTextReplySender = { _, message, _, _ ->
                    shareCalls.incrementAndGet()
                    capturedMessage.set(message)
                },
                nativeImageReplySender = StubNativeImageSender(),
                mediaPreparationService = null,
            )

        val cmd =
            ShareReplyCommand(
                chatId = 200L,
                message = "shared text",
                threadId = null,
                threadScope = null,
                requestId = null,
            )

        transport.sendShare(cmd)

        assertEquals(1, shareCalls.get())
        assertEquals("shared text", capturedMessage.get().toString())
    }

    @Test
    fun `dispatches native image via native sender and cleans up files`() {
        val nativeCalls = AtomicInteger(0)
        val capturedPaths = AtomicReference<List<String>>()
        val cleanupCalled = AtomicInteger(0)
        val tempFile = File.createTempFile("iris-transport-test", ".png")

        val transport =
            ReplyTransport(
                notificationReplySender = { _, _, _, _, _ -> },
                sharedTextReplySender = { _, _, _, _ -> },
                nativeImageReplySender =
                    object : NativeImageReplySender {
                        override fun send(
                            roomId: Long,
                            imagePaths: List<String>,
                            threadId: Long?,
                            threadScope: Int?,
                            requestId: String?,
                        ) {
                            nativeCalls.incrementAndGet()
                            capturedPaths.set(imagePaths)
                        }
                    },
                mediaPreparationService =
                    object : MediaPreparationCleanup {
                        override fun cleanup(preparedImages: PreparedImages) {
                            cleanupCalled.incrementAndGet()
                        }
                    },
            )

        val preparedImages =
            PreparedImages(
                room = 300L,
                imagePaths = arrayListOf(tempFile.absolutePath),
                files = arrayListOf(tempFile),
            )

        transport.sendNativeImages(
            command =
                NativeImageReplyCommand(
                    chatId = 300L,
                    base64Images = listOf("base64"),
                    threadId = null,
                    threadScope = null,
                    requestId = "img-1",
                ),
            preparedImages = preparedImages,
        )

        assertEquals(1, nativeCalls.get())
        assertEquals(listOf(tempFile.absolutePath), capturedPaths.get())
        assertEquals(1, cleanupCalled.get())
        tempFile.delete()
    }

    @Test
    fun `cleans up prepared images even when native send fails`() {
        val cleanupCalled = AtomicInteger(0)
        val tempFile = File.createTempFile("iris-transport-fail", ".png")

        val transport =
            ReplyTransport(
                notificationReplySender = { _: String, _: Long, _: CharSequence, _: Long?, _: Int? -> },
                sharedTextReplySender = { _: Long, _: CharSequence, _: Long?, _: Int? -> },
                nativeImageReplySender =
                    object : NativeImageReplySender {
                        override fun send(
                            roomId: Long,
                            imagePaths: List<String>,
                            threadId: Long?,
                            threadScope: Int?,
                            requestId: String?,
                        ): Unit = throw RuntimeException("native send failed")
                    },
                mediaPreparationService =
                    object : MediaPreparationCleanup {
                        override fun cleanup(preparedImages: PreparedImages) {
                            cleanupCalled.incrementAndGet()
                        }
                    },
            )

        val preparedImages =
            PreparedImages(
                room = 400L,
                imagePaths = arrayListOf(tempFile.absolutePath),
                files = arrayListOf(tempFile),
            )

        try {
            transport.sendNativeImages(
                command =
                    NativeImageReplyCommand(
                        chatId = 400L,
                        base64Images = listOf("base64"),
                        threadId = null,
                        threadScope = null,
                        requestId = null,
                    ),
                preparedImages = preparedImages,
            )
        } catch (_: RuntimeException) {
        }

        assertEquals(1, cleanupCalled.get(), "cleanup must run even on send failure")
        tempFile.delete()
    }

    @Test
    fun `preserves zero-width characters in text replies`() {
        val capturedMessage = AtomicReference<CharSequence>()

        val transport =
            ReplyTransport(
                notificationReplySender = { _: String, _: Long, message: CharSequence, _: Long?, _: Int? ->
                    capturedMessage.set(message)
                },
                sharedTextReplySender = { _: Long, _: CharSequence, _: Long?, _: Int? -> },
                nativeImageReplySender = StubNativeImageSender(),
                mediaPreparationService = null,
            )

        val cmd =
            TextReplyCommand(
                chatId = 100L,
                referer = "Iris",
                message = "a\u200Bb",
                threadId = null,
                threadScope = null,
                requestId = null,
            )

        transport.sendText(cmd)

        val result = capturedMessage.get().toString()
        assertEquals("a\u200B\uFEFFb", result)
    }

    private class StubNativeImageSender : NativeImageReplySender {
        override fun send(
            roomId: Long,
            imagePaths: List<String>,
            threadId: Long?,
            threadScope: Int?,
            requestId: String?,
        ) {
        }
    }
}
