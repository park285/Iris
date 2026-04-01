package party.qwer.iris

import kotlin.test.Test
import kotlin.test.assertEquals

class ObservedThreadMetadataTest {
    @Test
    fun `prefers direct thread columns for text messages`() {
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
                threadScope = 2,
                supplement = "{}",
            )

        assertEquals(
            ObservedThreadMetadata(threadId = "12345", threadScope = 2),
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
    fun `returns direct thread metadata for image messages`() {
        val logEntry =
            KakaoDB.ChatLogEntry(
                id = 1L,
                chatId = 10L,
                userId = 20L,
                message = "사진",
                metadata = """{"enc":0,"origin":"CHATLOG"}""",
                createdAt = "2026-03-21T00:00:00",
                messageType = "2",
                threadId = "12345",
                threadScope = 2,
                supplement = "{}",
            )

        assertEquals(
            ObservedThreadMetadata(threadId = "12345", threadScope = 2),
            resolveObservedThreadMetadata(logEntry, enc = 0),
        )
    }

    @Test
    fun `falls back to supplement thread metadata for non-text messages`() {
        val logEntry =
            KakaoDB.ChatLogEntry(
                id = 1L,
                chatId = 10L,
                userId = 20L,
                message = "!질문 hi",
                metadata = """{"enc":0,"origin":"CHATLOG"}""",
                createdAt = "2026-03-15T00:00:00",
                messageType = "2",
                threadId = null,
                threadScope = null,
                supplement = """{"threadId":"67890","scope":2}""",
            )

        assertEquals(
            ObservedThreadMetadata(threadId = "67890", threadScope = 2),
            resolveObservedThreadMetadata(logEntry, enc = 0),
        )
    }
}
