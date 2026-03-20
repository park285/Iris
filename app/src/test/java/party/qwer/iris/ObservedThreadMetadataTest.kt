package party.qwer.iris

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ObservedThreadMetadataTest {
    @Test
    fun `prefers explicit thread_id for text messages`() {
        val logEntry =
            KakaoDB.ChatLogEntry(
                id = 1L,
                chatId = 10L,
                userId = 20L,
                message = "!질문 hi",
                metadata = """{"enc":0,"origin":"CHATLOG"}""",
                createdAt = "2026-03-15T00:00:00",
                messageType = "1",
                threadId = "12345",
                supplement = "{}",
            )

        assertEquals(
            ObservedThreadMetadata(threadId = "12345"),
            resolveObservedThreadMetadata(logEntry, enc = 0),
        )
    }

    @Test
    fun `falls back to supplement thread metadata when thread_id is absent`() {
        val logEntry =
            KakaoDB.ChatLogEntry(
                id = 1L,
                chatId = 10L,
                userId = 20L,
                message = "!질문 hi",
                metadata = """{"enc":0,"origin":"CHATLOG"}""",
                createdAt = "2026-03-15T00:00:00",
                messageType = "1",
                threadId = null,
                supplement = """{"threadId":"67890","scope":2}""",
            )

        assertEquals(
            ObservedThreadMetadata(threadId = "67890", threadScope = 2),
            resolveObservedThreadMetadata(logEntry, enc = 0),
        )
    }

    @Test
    fun `ignores thread metadata for non-text messages`() {
        val logEntry =
            KakaoDB.ChatLogEntry(
                id = 1L,
                chatId = 10L,
                userId = 20L,
                message = "!질문 hi",
                metadata = """{"enc":0,"origin":"CHATLOG"}""",
                createdAt = "2026-03-15T00:00:00",
                messageType = "2",
                threadId = "12345",
                supplement = """{"threadId":"67890","scope":3}""",
            )

        assertNull(resolveObservedThreadMetadata(logEntry, enc = 0))
    }
}
