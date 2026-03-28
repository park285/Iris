package party.qwer.iris

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import party.qwer.iris.model.ConfigValues

private const val LEGACY_WEBHOOKS_KEY = "webhooks"
internal const val DEFAULT_WEBHOOK_ROUTE = "default"

internal data class DecodedConfigValues(
    val values: ConfigValues,
    val migratedLegacyEndpoint: Boolean,
    val migratedRoutingDefaults: Boolean,
)

internal fun decodeConfigValues(
    json: Json,
    jsonString: String,
): DecodedConfigValues {
    val rawRoot = json.parseToJsonElement(jsonString).jsonObject
    val migratedRoutingDefaults = requiresRoutingDefaultsMigration(rawRoot)
    val decodedValues =
        seedRoutingDefaults(
            json.decodeFromJsonElement(ConfigValues.serializer(), rawRoot),
        )
    val legacyEndpoint =
        decodedValues.endpoint
            .trim()
            .takeIf { it.isNotEmpty() }
            ?: extractLegacyEndpoint(rawRoot)
    val migratedLegacyEndpoint = decodedValues.endpoint.isBlank() && !legacyEndpoint.isNullOrBlank()
    val normalizedValues =
        normalizeWebhookConfig(
            if (migratedLegacyEndpoint) {
                decodedValues.copy(endpoint = legacyEndpoint.orEmpty())
            } else {
                decodedValues
            },
        )

    return DecodedConfigValues(
        values = normalizedValues,
        migratedLegacyEndpoint = migratedLegacyEndpoint,
        migratedRoutingDefaults = migratedRoutingDefaults,
    )
}

internal fun seedRoutingDefaults(values: ConfigValues): ConfigValues =
    values.copy(
        commandRoutePrefixes =
            values.commandRoutePrefixes.ifEmpty {
                DEFAULT_COMMAND_ROUTE_PREFIXES
            },
        imageMessageTypeRoutes =
            values.imageMessageTypeRoutes.ifEmpty {
                DEFAULT_IMAGE_MESSAGE_TYPE_ROUTES
            },
    )

internal fun requiresRoutingDefaultsMigration(root: JsonObject): Boolean =
    requiresRoutingDefaultsMigration(root, "commandRoutePrefixes") ||
        requiresRoutingDefaultsMigration(root, "imageMessageTypeRoutes")

private fun requiresRoutingDefaultsMigration(
    root: JsonObject,
    key: String,
): Boolean {
    if (!root.containsKey(key)) {
        return true
    }

    val element = root[key] ?: return true
    return element is JsonObject && element.isEmpty()
}

internal fun configuredWebhookEndpoint(
    values: ConfigValues,
    route: String,
): String {
    val normalizedRoute = canonicalWebhookRoute(route)
    if (normalizedRoute.isEmpty()) {
        return ""
    }

    val routeEndpoint = values.webhooks[normalizedRoute]?.trim().orEmpty()
    if (routeEndpoint.isNotEmpty()) {
        return routeEndpoint
    }

    return values.endpoint.trim()
}

internal fun normalizeWebhookConfig(values: ConfigValues): ConfigValues {
    val normalizedEndpoint = values.endpoint.trim()
    val normalizedWebhooks =
        values.webhooks.entries
            .asSequence()
            .mapNotNull { entry ->
                val route = canonicalWebhookRoute(entry.key)
                val endpoint = entry.value.trim()
                if (route.isEmpty() || endpoint.isEmpty()) {
                    null
                } else {
                    route to endpoint
                }
            }.toMap(linkedMapOf())
    val defaultEndpoint = normalizedWebhooks[DEFAULT_WEBHOOK_ROUTE].orEmpty().ifBlank { normalizedEndpoint }
    val syncedWebhooks =
        if (defaultEndpoint.isBlank()) {
            normalizedWebhooks
        } else {
            normalizedWebhooks + (DEFAULT_WEBHOOK_ROUTE to defaultEndpoint)
        }

    return values.copy(
        endpoint = defaultEndpoint,
        webhooks = syncedWebhooks,
    )
}

internal fun updateWebhookConfig(
    values: ConfigValues,
    route: String,
    endpoint: String,
): ConfigValues {
    val normalizedRoute = canonicalWebhookRoute(route)
    val normalizedEndpoint = endpoint.trim()
    val updatedWebhooks = values.webhooks.toMutableMap()

    if (normalizedEndpoint.isBlank()) {
        updatedWebhooks.remove(normalizedRoute)
    } else {
        updatedWebhooks[normalizedRoute] = normalizedEndpoint
    }

    val updatedValues =
        if (normalizedRoute == DEFAULT_WEBHOOK_ROUTE) {
            values.copy(
                endpoint = normalizedEndpoint,
                webhooks = updatedWebhooks,
            )
        } else {
            values.copy(webhooks = updatedWebhooks)
        }

    return normalizeWebhookConfig(updatedValues)
}

internal fun extractLegacyEndpoint(root: JsonObject): String? {
    val legacyWebhooks = root[LEGACY_WEBHOOKS_KEY]?.jsonObject ?: return null
    legacyWebhooks[DEFAULT_WEBHOOK_ROUTE]
        ?.jsonPrimitive
        ?.contentOrNull
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { return it }

    return null
}

internal fun canonicalWebhookRoute(rawRoute: String?): String = rawRoute?.trim().orEmpty()
