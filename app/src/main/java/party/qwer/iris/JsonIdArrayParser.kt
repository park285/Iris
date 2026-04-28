package party.qwer.iris

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import party.qwer.iris.nativecore.NativeCoreHolder

internal class JsonIdArrayParser(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun parse(raw: String?): Set<Long> =
        NativeCoreHolder.current().parseIdArrayOrFallback(raw) {
            parseKotlin(raw)
        }

    private fun parseKotlin(raw: String?): Set<Long> {
        if (raw.isNullOrBlank() || raw == "[]") {
            return emptySet()
        }
        return try {
            json
                .parseToJsonElement(raw)
                .jsonArray
                .mapNotNull { element -> element.jsonPrimitive.long }
                .toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }
}
