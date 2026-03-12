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
}
