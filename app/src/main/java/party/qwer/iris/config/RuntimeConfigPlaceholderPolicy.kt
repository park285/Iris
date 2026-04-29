package party.qwer.iris.config

private const val PLACEHOLDER_SECRET_VALUE = "change-me"
private const val PLACEHOLDER_WEBHOOK_HOST = "example.invalid"

internal fun containsUnresolvedConfigPlaceholder(value: String): Boolean = value.contains("\${")

internal fun isPlaceholderSecretValue(value: String): Boolean {
    val trimmed = value.trim()
    return trimmed.equals(PLACEHOLDER_SECRET_VALUE, ignoreCase = true) || containsUnresolvedConfigPlaceholder(trimmed)
}

internal fun isConfiguredRuntimeSecret(value: String): Boolean = value.trim().isNotEmpty() && !isPlaceholderSecretValue(value)

internal fun isPlaceholderWebhookEndpoint(value: String): Boolean {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) {
        return false
    }
    if (containsUnresolvedConfigPlaceholder(trimmed)) {
        return true
    }
    return webhookEndpointHost(trimmed)?.equals(PLACEHOLDER_WEBHOOK_HOST, ignoreCase = true) == true
}

private fun webhookEndpointHost(endpoint: String): String? {
    val withoutScheme =
        when {
            endpoint.startsWith("http://") -> endpoint.removePrefix("http://")
            endpoint.startsWith("https://") -> endpoint.removePrefix("https://")
            else -> return null
        }
    return withoutScheme
        .substringBefore("/")
        .substringBefore("?")
        .substringBefore("#")
        .substringBefore(":")
        .takeIf { it.isNotEmpty() }
}
