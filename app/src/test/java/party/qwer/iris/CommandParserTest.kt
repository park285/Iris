package party.qwer.iris

import kotlin.test.Test
import kotlin.test.assertEquals

class CommandParserTest {
    @Test
    fun `parses regular messages as none`() {
        val parsed = CommandParser.parse("hello")

        assertEquals(CommandKind.NONE, parsed.kind)
        assertEquals("hello", parsed.normalizedText)
    }

    @Test
    fun `treats double slash as comment with trim start`() {
        val parsed = CommandParser.parse("   // ignored")

        assertEquals(CommandKind.COMMENT, parsed.kind)
        assertEquals("// ignored", parsed.normalizedText)
    }

    @Test
    fun `treats bang command as webhook command`() {
        val parsed = CommandParser.parse("!ping")

        assertEquals(CommandKind.WEBHOOK, parsed.kind)
        assertEquals("!ping", parsed.normalizedText)
    }

    @Test
    fun `treats slash command as webhook command`() {
        val parsed = CommandParser.parse("   /ping")

        assertEquals(CommandKind.WEBHOOK, parsed.kind)
        assertEquals("/ping", parsed.normalizedText)
    }

    @Test
    fun `treats mention as none without bot mention support`() {
        val parsed = CommandParser.parse("@kapu봇 정산")

        assertEquals(CommandKind.NONE, parsed.kind)
    }

    @Test
    fun `handles edge case prefixes and blank input`() {
        val cases =
            listOf(
                Triple("", CommandKind.NONE, ""),
                Triple("   ", CommandKind.NONE, ""),
                Triple("!//", CommandKind.WEBHOOK, "!//"),
                Triple("!\nhello", CommandKind.WEBHOOK, "!\nhello"),
                Triple("!", CommandKind.WEBHOOK, "!"),
                Triple("/", CommandKind.WEBHOOK, "/"),
            )

        cases.forEach { (message, expectedKind, expectedNormalizedText) ->
            val parsed = CommandParser.parse(message)

            assertEquals(expectedKind, parsed.kind)
            assertEquals(expectedNormalizedText, parsed.normalizedText)
        }
    }
}
