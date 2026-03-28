package party.qwer.iris

import android.content.Intent
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReplyMarkdownIntentTest {
    @Test
    fun `build reply markdown intent spec targets kakao direct share with markdown metadata`() {
        val spec =
            buildReplyMarkdownIntentSpec(
                room = 18476130232878491L,
                text = "**고슴도치 귀여워**\n`code`",
            )

        assertEquals(Intent.ACTION_SEND, spec.action)
        assertEquals("com.kakao.talk", spec.packageName)
        assertEquals("com.kakao.talk.activity.RecentExcludeIntentFilterActivity", spec.className)
        assertEquals("com.kakao.talk", spec.callerPackageName)
        assertEquals("text/plain", spec.mimeType)
        assertTrue(spec.markdown)
        assertTrue(spec.markdownParam)
        assertTrue(spec.forceFlag)
        assertEquals("com.kakao.talk", spec.extraPackageName)
        val attachment = JSONObject(spec.extraChatAttachment)
        assertEquals("com.kakao.talk", attachment.getString("callingPkg"))
        assertTrue(attachment.getBoolean("markdown"))
        assertTrue(attachment.getBoolean("f"))
        assertEquals("com.kakao.talk.action.ACTION_SEND_CHAT_MESSAGE", spec.innerAction)
        assertEquals(1, spec.innerMessageTypeValue)
        assertEquals(18476130232878491L, spec.room)
        assertEquals(1, spec.keyType)
        assertTrue(spec.fromDirectShare)
        assertEquals("**고슴도치 귀여워**\n`code`", spec.text)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP, spec.flags)
    }

    @Test
    fun `reply markdown accepts threaded metadata`() {
        assertEquals(3, validateReplyMarkdownThreadMetadata(threadId = 123L, threadScope = 3))
    }

    @Test
    fun `reply markdown defaults threaded scope when omitted`() {
        assertEquals(2, validateReplyMarkdownThreadMetadata(threadId = 123L, threadScope = null))
    }

    @Test
    fun `build reply markdown spec keeps iris thread metadata when provided`() {
        val metadata =
            ReplyMarkdownThreadMetadata(
                threadId = 3805486995143352321L,
                threadScope = 2,
                sessionId = "session-1",
                createdAtEpochMs = 123456789L,
            )

        val spec =
            buildReplyMarkdownIntentSpec(
                room = 18476130232878491L,
                text = "**고슴도치 귀여워**",
                threadMetadata = metadata,
            )

        assertEquals(metadata, spec.threadMetadata)
        assertTrue(spec.extraChatAttachment.contains("\"irisSessionId\":\"session-1\""))
    }
}
