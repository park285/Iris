package party.qwer.iris

import party.qwer.iris.nativecore.NativeCoreHolder

enum class CommandKind {
    NONE,
    COMMENT,
    WEBHOOK,
}

data class ParsedCommand(
    val kind: CommandKind,
    val normalizedText: String,
)

object CommandParser {
    fun parse(message: String): ParsedCommand =
        NativeCoreHolder.current().parseCommandOrFallback(message) {
            parseKotlin(message)
        }

    private fun parseKotlin(message: String): ParsedCommand {
        val normalizedText = message.trimStart()
        val kind =
            when {
                normalizedText.startsWith(PREFIX_COMMENT) -> CommandKind.COMMENT
                normalizedText.startsWith(PREFIX_BANG) || normalizedText.startsWith(PREFIX_SLASH) -> CommandKind.WEBHOOK
                else -> CommandKind.NONE
            }

        return ParsedCommand(
            kind = kind,
            normalizedText = normalizedText,
        )
    }

    private const val PREFIX_BANG = "!"
    private const val PREFIX_SLASH = "/"
    private const val PREFIX_COMMENT = "//"
}
