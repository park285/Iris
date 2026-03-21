package party.qwer.iris

import party.qwer.iris.model.ConfigRequest

internal data class ConfigUpdateOutcome(
    val name: String,
    val applied: Boolean,
    val requiresRestart: Boolean,
)

internal fun applyConfigUpdate(name: String, request: ConfigRequest): ConfigUpdateOutcome =
    when (name) {
        "endpoint" -> updateEndpointConfig(name, request)
        "dbrate" -> updateDbRateConfig(name, request)
        "sendrate" -> updateSendRateConfig(name, request)
        "botport" -> updateBotPortConfig(name, request)
        else -> throw ApiRequestException("unknown config '$name'")
    }

private fun updateEndpointConfig(name: String, request: ConfigRequest): ConfigUpdateOutcome {
    val route = resolveEndpointRoute(request.route)
    val value = request.endpoint?.trim() ?: throw ApiRequestException("missing endpoint value")
    if (value.isNotEmpty() && !value.startsWith("http://") && !value.startsWith("https://")) {
        throw ApiRequestException("endpoint must start with http:// or https://")
    }
    Configurable.setWebhookEndpoint(route, value)
    return ConfigUpdateOutcome(
        name = if (route == DEFAULT_WEBHOOK_ROUTE) name else "$name.$route",
        applied = true,
        requiresRestart = false,
    )
}

private fun updateDbRateConfig(name: String, request: ConfigRequest): ConfigUpdateOutcome {
    val value = request.rate ?: throw ApiRequestException("missing or invalid rate")
    if (value < 1) throw ApiRequestException("rate must be greater than 0")
    Configurable.dbPollingRate = value
    return ConfigUpdateOutcome(name = name, applied = true, requiresRestart = false)
}

private fun updateSendRateConfig(name: String, request: ConfigRequest): ConfigUpdateOutcome {
    val value = request.rate ?: throw ApiRequestException("missing or invalid rate")
    if (value < 0) throw ApiRequestException("rate must be 0 or greater")
    Configurable.messageSendRate = value
    return ConfigUpdateOutcome(name = name, applied = true, requiresRestart = false)
}

private fun updateBotPortConfig(name: String, request: ConfigRequest): ConfigUpdateOutcome {
    val value = request.port ?: throw ApiRequestException("missing or invalid port")
    if (value < 1 || value > 65535) throw ApiRequestException("invalid port number; port must be between 1 and 65535")
    Configurable.botSocketPort = value
    return ConfigUpdateOutcome(name = name, applied = false, requiresRestart = true)
}

private fun resolveEndpointRoute(rawRoute: String?): String {
    val normalized = rawRoute?.trim().orEmpty()
    if (normalized.isEmpty()) return DEFAULT_WEBHOOK_ROUTE
    if (!normalized.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
        throw ApiRequestException("route must contain only letters, digits, '-' or '_'")
    }
    return normalized
}
