package party.qwer.iris

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import party.qwer.iris.model.ConfigValues

private const val LEGACY_WEBHOOKS_KEY = "webhooks"
private const val LEGACY_DEFAULT_WEBHOOK_ROUTE = "hololive"

internal data class DecodedConfigValues(
    val values: ConfigValues,
    val migratedLegacyEndpoint: Boolean,
)

internal fun decodeConfigValues(
    json: Json,
    jsonString: String,
): DecodedConfigValues {
    val rawRoot = json.parseToJsonElement(jsonString).jsonObject
    val decodedValues = json.decodeFromJsonElement(ConfigValues.serializer(), rawRoot)
    if (decodedValues.endpoint.isNotBlank()) {
        return DecodedConfigValues(values = decodedValues, migratedLegacyEndpoint = false)
    }

    val legacyEndpoint = extractLegacyEndpoint(rawRoot) ?: return DecodedConfigValues(decodedValues, false)
    return DecodedConfigValues(
        values = decodedValues.copy(endpoint = legacyEndpoint),
        migratedLegacyEndpoint = true,
    )
}

internal fun extractLegacyEndpoint(root: JsonObject): String? {
    val legacyWebhooks = root[LEGACY_WEBHOOKS_KEY]?.jsonObject ?: return null
    legacyWebhooks[LEGACY_DEFAULT_WEBHOOK_ROUTE]
        ?.jsonPrimitive
        ?.contentOrNull
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { return it }

    return legacyWebhooks.values
        .firstNotNullOfOrNull { entry ->
            entry.jsonPrimitive.contentOrNull
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        }
}
