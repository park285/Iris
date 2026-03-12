package party.qwer.iris

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class KakaoDBBotUserIdResolutionTest {
    @Test
    fun `prefers detected bot id when available`() {
        val resolution = resolveBotUserId(detectedBotId = 123L, configuredBotId = 456L)

        assertIs<BotUserIdResolution.Detected>(resolution)
        assertEquals(123L, resolution.botId)
    }

    @Test
    fun `falls back to configured bot id when detection fails`() {
        val resolution = resolveBotUserId(detectedBotId = 0L, configuredBotId = 456L)

        assertIs<BotUserIdResolution.ConfigFallback>(resolution)
        assertEquals(456L, resolution.botId)
    }

    @Test
    fun `returns missing when neither detected nor configured bot id exists`() {
        val resolution = resolveBotUserId(detectedBotId = 0L, configuredBotId = 0L)

        assertIs<BotUserIdResolution.Missing>(resolution)
        assertEquals(0L, resolution.botId)
    }
}
