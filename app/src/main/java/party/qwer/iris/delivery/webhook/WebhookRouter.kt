package party.qwer.iris.delivery.webhook

import party.qwer.iris.CHATBOTGO_ROUTE
import party.qwer.iris.CommandKind
import party.qwer.iris.CommandParser
import party.qwer.iris.DEFAULT_WEBHOOK_ROUTE
import party.qwer.iris.ParsedCommand

internal fun resolveWebhookRoute(commandText: String): String? = resolveWebhookRoute(CommandParser.parse(commandText))

internal fun resolveWebhookRoute(parsedCommand: ParsedCommand): String? = resolveWebhookRoute(parsedCommand, null)

private val chatbotgoEventTypes =
    setOf(
        "nickname_change",
        "profile_change",
    )

internal fun resolveWebhookRoute(
    parsedCommand: ParsedCommand,
    config: party.qwer.iris.ConfigProvider?,
): String? {
    if (parsedCommand.kind != CommandKind.WEBHOOK) {
        return null
    }

    val text = parsedCommand.normalizedText
    val configuredPrefixes =
        config
            ?.commandRoutePrefixes()
            .orEmpty()
    if (configuredPrefixes.isEmpty()) {
        return DEFAULT_WEBHOOK_ROUTE
    }

    return configuredPrefixes.entries
        .firstOrNull { (_, prefixes) ->
            prefixes.any { command -> matchesCommandPrefix(text, command) }
        }?.key ?: DEFAULT_WEBHOOK_ROUTE
}

internal fun resolveEventRoute(messageType: String?): String? {
    val normalizedType = messageType?.trim().orEmpty()
    if (normalizedType.isEmpty()) return null

    if (normalizedType !in chatbotgoEventTypes) {
        return null
    }

    return CHATBOTGO_ROUTE
}

internal fun resolveImageRoute(messageType: String?): String? = resolveImageRoute(messageType, null)

internal fun resolveImageRoute(
    messageType: String?,
    config: party.qwer.iris.ConfigProvider?,
): String? {
    val normalizedType = messageType?.trim().orEmpty()
    if (normalizedType.isEmpty()) return null

    val configuredRoutes =
        config
            ?.imageMessageTypeRoutes()
            .orEmpty()
    if (configuredRoutes.isEmpty()) return null

    return configuredRoutes.entries.firstOrNull { (_, types) -> normalizedType in types }?.key
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
