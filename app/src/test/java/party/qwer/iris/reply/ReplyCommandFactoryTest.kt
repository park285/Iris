package party.qwer.iris.reply

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class ReplyCommandFactoryTest {
    private val factory = ReplyCommandFactory()

    @Test
    fun `creates text reply command for room message`() {
        val cmd =
            factory.textReply(
                referer = "Iris",
                chatId = 100L,
                message = "hello",
                threadId = null,
                threadScope = null,
                requestId = "req-1",
            )

        assertIs<TextReplyCommand>(cmd)
        assertEquals(100L, cmd.chatId)
        assertEquals("hello", cmd.message)
        assertNull(cmd.threadId)
        assertNull(cmd.threadScope)
        assertEquals("req-1", cmd.requestId)
        assertEquals("Iris", cmd.referer)
    }

    @Test
    fun `creates text reply command for threaded message`() {
        val cmd =
            factory.textReply(
                referer = "Iris",
                chatId = 100L,
                message = "threaded",
                threadId = 500L,
                threadScope = 2,
                requestId = null,
            )

        assertIs<TextReplyCommand>(cmd)
        assertEquals(500L, cmd.threadId)
        assertEquals(2, cmd.threadScope)
    }

    @Test
    fun `creates native image reply command with single image`() {
        val cmd =
            factory.nativeImageReply(
                chatId = 200L,
                base64Images = listOf("aW1hZ2U="),
                threadId = null,
                threadScope = null,
                requestId = "img-1",
            )

        assertIs<NativeImageReplyCommand>(cmd)
        assertEquals(200L, cmd.chatId)
        assertEquals(listOf("aW1hZ2U="), cmd.base64Images)
        assertNull(cmd.threadId)
        assertEquals("img-1", cmd.requestId)
    }

    @Test
    fun `creates native image reply command with multiple images`() {
        val images = listOf("aW1hZ2Ux", "aW1hZ2Uy", "aW1hZ2Uz")
        val cmd =
            factory.nativeImageReply(
                chatId = 200L,
                base64Images = images,
                threadId = 300L,
                threadScope = 2,
                requestId = "img-multi",
            )

        assertIs<NativeImageReplyCommand>(cmd)
        assertEquals(3, cmd.base64Images.size)
        assertEquals(300L, cmd.threadId)
    }

    @Test
    fun `creates share reply command for text share`() {
        val cmd =
            factory.shareReply(
                chatId = 300L,
                message = "shared",
                threadId = null,
                threadScope = null,
                requestId = "share-1",
            )

        assertIs<ShareReplyCommand>(cmd)
        assertEquals(300L, cmd.chatId)
        assertEquals("shared", cmd.message)
        assertNull(cmd.threadId)
    }

    @Test
    fun `creates share reply command for markdown`() {
        val cmd =
            factory.shareReply(
                chatId = 300L,
                message = "**bold**",
                threadId = 400L,
                threadScope = 2,
                requestId = "md-1",
            )

        assertIs<ShareReplyCommand>(cmd)
        assertEquals(400L, cmd.threadId)
        assertEquals(2, cmd.threadScope)
    }

    @Test
    fun `native image reply rejects empty image list`() {
        assertFailsWith<IllegalArgumentException> {
            factory.nativeImageReply(
                chatId = 200L,
                base64Images = emptyList(),
                threadId = null,
                threadScope = null,
                requestId = null,
            )
        }
    }
}
