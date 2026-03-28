package party.qwer.iris.delivery.webhook

import party.qwer.iris.CommandKind
import party.qwer.iris.CommandParser
import party.qwer.iris.DEFAULT_WEBHOOK_ROUTE
import party.qwer.iris.ParsedCommand

internal const val ROUTE_DEFAULT = DEFAULT_WEBHOOK_ROUTE
internal const val ROUTE_CHATBOTGO = "chatbotgo"
internal const val ROUTE_SETTLEMENT = "settlement"

private data class CommandRouteRule(
    val route: String,
    val prefixes: Set<String>,
)

private val commandRouteRules =
    listOf(
        CommandRouteRule(
            route = ROUTE_SETTLEMENT,
            prefixes =
                setOf(
                    "!정산",
                    "!정산완료",
                ),
        ),
        CommandRouteRule(
            route = ROUTE_CHATBOTGO,
            prefixes =
                setOf(
                    "!질문",
                    "!이미지",
                    "!그림",
                    "!리셋",
                    "!관리자",
                    "!한강",
                ),
        ),
    )

private val defaultImageRouteRules =
    mapOf(
        ROUTE_CHATBOTGO to setOf("2"),
    )

internal fun resolveWebhookRoute(commandText: String): String? = resolveWebhookRoute(CommandParser.parse(commandText))

internal fun resolveWebhookRoute(parsedCommand: ParsedCommand): String? = resolveWebhookRoute(parsedCommand, null)

internal fun resolveWebhookRoute(
    parsedCommand: ParsedCommand,
    config: party.qwer.iris.ConfigProvider?,
): String? {
    if (parsedCommand.kind != CommandKind.WEBHOOK) {
        return null
    }

    val text = parsedCommand.normalizedText
    val effectiveRules =
        config
            ?.commandRoutePrefixes()
            ?.takeIf { it.isNotEmpty() }
            ?.map { (route, prefixes) ->
                CommandRouteRule(
                    route = route,
                    prefixes = prefixes.toSet(),
                )
            } ?: commandRouteRules
    return effectiveRules
        .firstOrNull { rule ->
            rule.prefixes.any { command -> matchesCommandPrefix(text, command) }
        }?.route ?: ROUTE_DEFAULT
}

private const val IMAGE_MESSAGE_TYPE = "2"

internal fun resolveImageRoute(messageType: String?): String? = resolveImageRoute(messageType, null)

internal fun resolveImageRoute(
    messageType: String?,
    config: party.qwer.iris.ConfigProvider?,
): String? {
    val normalizedType = messageType?.trim().orEmpty()
    if (normalizedType.isEmpty()) return null
    val configured =
        config
            ?.imageMessageTypeRoutes()
            ?.takeIf { it.isNotEmpty() }
            ?.mapValues { (_, types) -> types.toSet() }
            ?: defaultImageRouteRules
    return configured.entries.firstOrNull { (_, types) -> normalizedType in types }?.key
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
