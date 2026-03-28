package party.qwer.iris.imagebridge.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ReplyMarkdownPendingContextStoreTest {
    @Test
    fun `match removes first exact room and message context`() {
        var now = 1_000L
        val store = ReplyMarkdownPendingContextStore(clock = { now })
        val context =
            ReplyMarkdownPendingContext(
                roomId = 7L,
                messageText = "hello",
                threadId = 55L,
                threadScope = 2,
                createdAtEpochMs = now,
            )

        store.remember(context)

        assertEquals(context, store.match(roomId = 7L, messageText = "hello"))
        assertNull(store.match(roomId = 7L, messageText = "hello"))
    }

    @Test
    fun `match skips stale entries`() {
        var now = 1_000L
        val store = ReplyMarkdownPendingContextStore(ttlMs = 100L, clock = { now })
        store.remember(
            ReplyMarkdownPendingContext(
                roomId = 7L,
                messageText = "stale",
                threadId = 1L,
                threadScope = 2,
                createdAtEpochMs = now,
            ),
        )

        now += 101L

        assertNull(store.match(roomId = 7L, messageText = "stale"))
        assertEquals(0, store.size())
    }

    @Test
    fun `duplicate contexts are matched in fifo order`() {
        val store = ReplyMarkdownPendingContextStore()
        val first =
            ReplyMarkdownPendingContext(
                roomId = 9L,
                messageText = "same",
                threadId = 10L,
                threadScope = 2,
                sessionId = "first",
                createdAtEpochMs = 1L,
            )
        val second =
            ReplyMarkdownPendingContext(
                roomId = 9L,
                messageText = "same",
                threadId = 11L,
                threadScope = 3,
                sessionId = "second",
                createdAtEpochMs = 2L,
            )

        store.remember(first)
        store.remember(second)

        assertEquals(first, store.match(roomId = 9L, messageText = "same"))
        assertEquals(second, store.match(roomId = 9L, messageText = "same"))
    }

    @Test
    fun `same message in same room matches by session id when present`() {
        val store = ReplyMarkdownPendingContextStore()
        val first =
            ReplyMarkdownPendingContext(
                roomId = 9L,
                messageText = "same",
                threadId = 10L,
                threadScope = 2,
                sessionId = "first",
                createdAtEpochMs = 1L,
            )
        val second =
            ReplyMarkdownPendingContext(
                roomId = 9L,
                messageText = "same",
                threadId = 11L,
                threadScope = 3,
                sessionId = "second",
                createdAtEpochMs = 2L,
            )

        store.remember(first)
        store.remember(second)

        assertEquals(first, store.match(roomId = 9L, messageText = "same", sessionId = "first"))
        assertEquals(second, store.match(roomId = 9L, messageText = "same", sessionId = "second"))
    }

    @Test
    fun `session specific match prefers exact session over older sessionless entry`() {
        val store = ReplyMarkdownPendingContextStore()
        val sessionless =
            ReplyMarkdownPendingContext(
                roomId = 9L,
                messageText = "same",
                threadId = 10L,
                threadScope = 2,
                sessionId = null,
                createdAtEpochMs = 1L,
            )
        val sessionSpecific =
            ReplyMarkdownPendingContext(
                roomId = 9L,
                messageText = "same",
                threadId = 11L,
                threadScope = 3,
                sessionId = "session-1",
                createdAtEpochMs = 2L,
            )

        store.remember(sessionless)
        store.remember(sessionSpecific)

        assertEquals(sessionSpecific, store.match(roomId = 9L, messageText = "same", sessionId = "session-1"))
        assertEquals(sessionless, store.match(roomId = 9L, messageText = "same"))
    }

    @Test
    fun `ingress capture stores extracted context`() {
        val store = ReplyMarkdownPendingContextStore()
        val captured =
            ReplyMarkdownIngressCapture.capture(
                intent = null,
                store = store,
                onCaptured = {},
            )

        assertNull(captured)
        val extracted =
            ReplyMarkdownBridgeExtras.extractPendingContext(
                ReplyMarkdownBridgeExtras.Snapshot(
                    roomIdRaw = "18478615493603057",
                    threadIdRaw = "3805486995143352321",
                    threadScope = 2,
                    messageText = "thread text",
                ),
            )
        assertNotNull(extracted)
        store.remember(extracted)
        assertEquals(extracted, store.match(roomId = 18478615493603057L, messageText = "thread text"))
    }
}

class ReplyMarkdownBridgeExtrasTest {
    @Test
    fun `extract pending context reads extras and nested message`() {
        val context =
            ReplyMarkdownBridgeExtras.extractPendingContext(
                ReplyMarkdownBridgeExtras.Snapshot(
                    sessionId = "session-1",
                    roomIdRaw = "18478615493603057",
                    threadIdRaw = "3805486995143352321",
                    threadScope = 3,
                    createdAtEpochMs = 99L,
                    nestedMessageText = "markdown text",
                ),
                nowEpochMs = 5L,
            )

        assertNotNull(context)
        assertEquals(18478615493603057L, context.roomId)
        assertEquals("markdown text", context.messageText)
        assertEquals(3805486995143352321L, context.threadId)
        assertEquals(3, context.threadScope)
        assertEquals("session-1", context.sessionId)
        assertEquals(99L, context.createdAtEpochMs)
    }

    @Test
    fun `extract pending context defaults scope to two`() {
        val context =
            ReplyMarkdownBridgeExtras.extractPendingContext(
                ReplyMarkdownBridgeExtras.Snapshot(
                    roomIdRaw = "1",
                    threadIdRaw = "2",
                    messageText = "hi",
                ),
                nowEpochMs = 7L,
            )

        assertNotNull(context)
        assertEquals(2, context.threadScope)
        assertEquals(7L, context.createdAtEpochMs)
    }

    @Test
    fun `extract pending context accepts numeric snapshot values`() {
        val context =
            ReplyMarkdownBridgeExtras.extractPendingContext(
                ReplyMarkdownBridgeExtras.Snapshot(
                    roomIdRaw = 18478615493603057L.toString(),
                    threadIdRaw = 3805486995143352321L.toString(),
                    threadScope = 2,
                    createdAtEpochMs = 99L,
                    messageText = "numeric extras",
                ),
                nowEpochMs = 5L,
            )

        assertNotNull(context)
        assertEquals(18478615493603057L, context.roomId)
        assertEquals(3805486995143352321L, context.threadId)
        assertEquals(2, context.threadScope)
        assertEquals(99L, context.createdAtEpochMs)
    }

    @Test
    fun `extract pending context skips non threaded markdown intents`() {
        assertNull(
            ReplyMarkdownBridgeExtras.extractPendingContext(
                ReplyMarkdownBridgeExtras.Snapshot(
                    roomIdRaw = "1",
                    messageText = "hi",
                ),
            ),
        )
    }
}

class ReplyMarkdownSendingLogAccessTest {
    @Test
    fun `reads sending log room and message`() {
        val log = FakeSendingLogWithSetters()
        log.message = "hello"
        log.roomId = 12L

        assertEquals(12L, ReplyMarkdownSendingLogAccess.readRoomId(log))
        assertEquals("hello", ReplyMarkdownSendingLogAccess.readMessageText(log))
    }

    @Test
    fun `writes thread metadata through setters when available`() {
        val log = FakeSendingLogWithSetters()

        ReplyMarkdownSendingLogAccess.writeThreadMetadata(log, threadId = 123L, threadScope = 3)

        assertEquals(123L, log.threadId)
        assertEquals(3, log.scope)
    }

    @Test
    fun `writes thread metadata through fields when setters are absent`() {
        val log = FakeSendingLogWithFields()

        ReplyMarkdownSendingLogAccess.writeThreadMetadata(log, threadId = 456L, threadScope = 2)

        assertEquals(456L, log.V0)
        assertEquals(2, log.Z)
    }

    @Test
    fun `reads session id from string attachment`() {
        val log = FakeSendingLogWithAttachmentField("""{"callingPkg":"com.kakao.talk","irisSessionId":"session-1"}""")

        assertEquals("session-1", ReplyMarkdownSendingLogAccess.readAttachmentSessionId(log))
    }
}

class ReplyMarkdownRequestSelectorTest {
    @Test
    fun `selector prefers request method with expected parameter types`() {
        val selected =
            selectReplyMarkdownRequestHookMethodForTest(
                FakeRequestCompanion::class.java,
                chatRoomClass = FakeMarkdownChatRoom::class.java,
                writeTypeClass = FakeMarkdownWriteType::class.java,
                listenerClass = FakeMarkdownListener::class.java,
            )

        assertNotNull(selected)
        assertEquals(
            listOf(
                FakeMarkdownChatRoom::class.java,
                Any::class.java,
                FakeMarkdownWriteType::class.java,
                FakeMarkdownListener::class.java,
                Boolean::class.javaPrimitiveType,
            ),
            selected.parameterTypes.toList(),
        )
    }
}

private class FakeSendingLogWithSetters {
    var roomId: Long = 0L
    var message: String = ""
    var threadId: Long? = null
    var scope: Int = 0

    fun getChatRoomId(): Long = roomId

    fun f0(): String = message

    @Suppress("FunctionName")
    fun H1(value: Int) {
        scope = value
    }

    @Suppress("FunctionName")
    fun J1(value: Long?) {
        threadId = value
    }
}

private class FakeSendingLogWithFields {
    @Suppress("PropertyName")
    var V0: Long? = null

    @Suppress("PropertyName")
    var Z: Int = 0
}

private class FakeSendingLogWithAttachmentField(
    @Suppress("PropertyName")
    var G: String,
)

private class FakeMarkdownChatRoom

private class FakeMarkdownOtherChatRoom

private class FakeMarkdownWriteType

private interface FakeMarkdownListener

@Suppress("UNUSED_PARAMETER")
private class FakeRequestCompanion {
    fun u(
        chatRoom: FakeMarkdownOtherChatRoom,
        sendingLog: Any,
        writeType: FakeMarkdownWriteType,
        listener: FakeMarkdownListener,
        immediate: Boolean,
    ) = Unit

    fun u(
        chatRoom: FakeMarkdownChatRoom,
        sendingLog: Any,
        writeType: FakeMarkdownWriteType,
        listener: FakeMarkdownListener,
        immediate: Boolean,
    ) = Unit
}
