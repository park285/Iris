package party.qwer.iris

import party.qwer.iris.config.ConfigField
import party.qwer.iris.config.ConfigPolicy
import party.qwer.iris.model.ConfigRequest

internal data class ConfigUpdateOutcome(
    val name: String,
    val applied: Boolean,
    val requiresRestart: Boolean,
)

internal fun applyConfigUpdate(
    configManager: ConfigManager,
    name: String,
    request: ConfigRequest,
): ConfigUpdateOutcome =
    when (name) {
        "endpoint" -> updateEndpointConfig(configManager, name, request)
        "dbrate" -> updateDbRateConfig(configManager, name, request)
        "sendrate" -> updateSendRateConfig(configManager, name, request)
        "botport" -> updateBotPortConfig(configManager, name, request)
        else -> throw ApiRequestException("unknown config '$name'")
    }

private fun updateEndpointConfig(
    configManager: ConfigManager,
    name: String,
    request: ConfigRequest,
): ConfigUpdateOutcome {
    val route = resolveEndpointRoute(request.route)
    val value = request.endpoint?.trim() ?: throw ApiRequestException("missing endpoint value")
    ConfigPolicy.validateWebhookEndpoint(value)?.let { throw ApiRequestException(it) }
    configManager.setWebhookEndpoint(route, value)
    return ConfigUpdateOutcome(
        name = if (route == DEFAULT_WEBHOOK_ROUTE) name else "$name.$route",
        applied = true,
        requiresRestart = false,
    )
}

private fun updateDbRateConfig(
    configManager: ConfigManager,
    name: String,
    request: ConfigRequest,
): ConfigUpdateOutcome {
    val value = request.rate ?: throw ApiRequestException("missing or invalid rate")
    requireValidState(
        field = ConfigField.DB_POLLING_RATE,
        state = configManager.snapshotUserState().copy(dbPollingRate = value),
    )
    configManager.dbPollingRate = value
    return ConfigUpdateOutcome(
        name = name,
        applied = true,
        requiresRestart = ConfigPolicy.requiresRestart(ConfigField.DB_POLLING_RATE),
    )
}

private fun updateSendRateConfig(
    configManager: ConfigManager,
    name: String,
    request: ConfigRequest,
): ConfigUpdateOutcome {
    val value = request.rate ?: throw ApiRequestException("missing or invalid rate")
    requireValidState(
        field = ConfigField.MESSAGE_SEND_RATE,
        state = configManager.snapshotUserState().copy(messageSendRate = value),
    )
    configManager.messageSendRate = value
    return ConfigUpdateOutcome(
        name = name,
        applied = true,
        requiresRestart = ConfigPolicy.requiresRestart(ConfigField.MESSAGE_SEND_RATE),
    )
}

private fun updateBotPortConfig(
    configManager: ConfigManager,
    name: String,
    request: ConfigRequest,
): ConfigUpdateOutcome {
    val value = request.port ?: throw ApiRequestException("missing or invalid port")
    requireValidState(
        field = ConfigField.BOT_SOCKET_PORT,
        state = configManager.snapshotUserState().copy(botHttpPort = value),
    )
    configManager.botSocketPort = value
    return ConfigUpdateOutcome(
        name = name,
        applied = false,
        requiresRestart = ConfigPolicy.requiresRestart(ConfigField.BOT_SOCKET_PORT),
    )
}

private fun resolveEndpointRoute(rawRoute: String?): String {
    val normalized = canonicalWebhookRoute(rawRoute)
    if (normalized.isEmpty()) return DEFAULT_WEBHOOK_ROUTE
    ConfigPolicy.validateWebhookRoute(normalized)?.let { throw ApiRequestException(it) }
    return normalized
}

private fun requireValidState(
    field: ConfigField,
    state: UserConfigState,
) {
    ConfigPolicy.validateField(field, state)?.let { error ->
        throw ApiRequestException(error.message)
    }
}
