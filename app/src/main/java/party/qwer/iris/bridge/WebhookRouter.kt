package party.qwer.iris.bridge

import party.qwer.iris.CommandKind
import party.qwer.iris.CommandParser
import party.qwer.iris.ParsedCommand

internal const val ROUTE_HOLOLIVE = "hololive"
internal const val ROUTE_CHATBOTGO = "chatbotgo"
internal const val ROUTE_SETTLEMENT = "settlement"

private val CHATBOTGO_COMMAND_PREFIXES =
    setOf(
        "!질문",
        "!리셋",
        "!관리자",
        "!한강",
    )

private val SETTLEMENT_COMMAND_PREFIXES =
    setOf(
        "!정산",
        "!정산완료",
    )

internal fun resolveWebhookRoute(commandText: String): String? = resolveWebhookRoute(CommandParser.parse(commandText))

internal fun resolveWebhookRoute(parsedCommand: ParsedCommand): String? {
    if (parsedCommand.kind != CommandKind.WEBHOOK) {
        return null
    }

    val text = parsedCommand.normalizedText
    return when {
        isSettlementCommand(text) -> ROUTE_SETTLEMENT
        isChatbotgoCommand(text) -> ROUTE_CHATBOTGO
        else -> ROUTE_HOLOLIVE
    }
}

private fun isSettlementCommand(normalizedText: String): Boolean =
    SETTLEMENT_COMMAND_PREFIXES.any { command ->
        matchesCommandPrefix(normalizedText, command)
    }

private fun isChatbotgoCommand(normalizedText: String): Boolean =
    CHATBOTGO_COMMAND_PREFIXES.any { command ->
        matchesCommandPrefix(normalizedText, command)
    }

private fun matchesCommandPrefix(
    raw: String,
    command: String,
): Boolean {
    if (!raw.startsWith(command)) {
        return false
    }
    if (raw.length == command.length) {
        return true
    }

    return raw[command.length].isWhitespace()
}
