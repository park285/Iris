package party.qwer.iris

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ObserverHelperLogicTest {
    private val config = ConfigManager(configPath = "/tmp/iris-observer-helper-test-config.json")
    private val originalBotId = config.botId

    @AfterTest
    fun tearDown() {
        config.botId = originalBotId
    }

    @Test
    fun `skips only sync style origins for none commands`() {
        val cases =
            listOf(
                Triple("SYNCMSG", CommandKind.NONE, true),
                Triple("SYNCMSG", CommandKind.WEBHOOK, false),
                Triple("CHATLOG", CommandKind.NONE, false),
                Triple(null, CommandKind.NONE, false),
            )

        cases.forEach { (origin, kind, expected) ->
            val parsedCommand = ParsedCommand(kind = kind, normalizedText = "message")

            assertEquals(expected, shouldSkipOrigin(origin, parsedCommand))
        }
    }

    @Test
    fun `bot id zero disables own bot message detection`() {
        config.botId = 0L

        assertFalse(isOwnBotMessage(0L, config.botId))
        assertFalse(isOwnBotMessage(123L, config.botId))
    }

    @Test
    fun `returns true only when user id matches configured bot id`() {
        config.botId = 42L

        assertTrue(isOwnBotMessage(42L, config.botId))
        assertFalse(isOwnBotMessage(41L, config.botId))
    }

    @Test
    fun `command fingerprint equality depends on all fields`() {
        val fingerprint =
            ObserverHelper.CommandFingerprint(
                chatId = 1L,
                userId = 2L,
                createdAt = "2026-03-19T00:00:00Z",
                message = "!ping",
            )
        val sameFingerprint =
            ObserverHelper.CommandFingerprint(
                chatId = 1L,
                userId = 2L,
                createdAt = "2026-03-19T00:00:00Z",
                message = "!ping",
            )
        val differentFingerprint =
            ObserverHelper.CommandFingerprint(
                chatId = 1L,
                userId = 2L,
                createdAt = "2026-03-19T00:00:01Z",
                message = "!ping",
            )

        assertEquals(fingerprint, sameFingerprint)
        assertNotEquals(fingerprint, differentFingerprint)
    }
}
