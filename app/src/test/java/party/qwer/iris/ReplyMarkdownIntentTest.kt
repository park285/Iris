package party.qwer.iris

import android.content.Intent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
        assertEquals("{\"callingPkg\":\"com.kakao.talk\",\"markdown\":true,\"f\":true}", spec.extraChatAttachment)
        assertEquals("com.kakao.talk.action.ACTION_SEND_CHAT_MESSAGE", spec.innerAction)
        assertEquals(1, spec.innerMessageTypeValue)
        assertEquals(18476130232878491L, spec.room)
        assertEquals(1, spec.keyType)
        assertTrue(spec.fromDirectShare)
        assertEquals("**고슴도치 귀여워**\n`code`", spec.text)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP, spec.flags)
    }

    @Test
    fun `reply markdown rejects thread metadata`() {
        val error =
            assertFailsWith<IllegalArgumentException> {
                validateReplyMarkdownThreadMetadata(threadId = 123L, threadScope = null)
            }

        assertEquals("reply-markdown does not support thread metadata", error.message)
    }
}
