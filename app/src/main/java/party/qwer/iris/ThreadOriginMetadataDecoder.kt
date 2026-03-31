package party.qwer.iris

import kotlinx.serialization.json.Json
import party.qwer.iris.model.ThreadOriginMetadata

internal class ThreadOriginMetadataDecoder(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun decode(raw: String?): ThreadOriginMetadata? {
        if (raw.isNullOrBlank()) {
            return null
        }
        return runCatching {
            json.decodeFromString<ThreadOriginMetadata>(raw)
        }.getOrNull()
    }
}
