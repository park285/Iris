package party.qwer.iris

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigSerializationTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
        }

    @Test
    fun `decodes single endpoint config without migration`() {
        val decoded =
            decodeConfigValues(
                json,
                """
                {
                  "botName": "Iris",
                  "endpoint": "http://example",
                  "botHttpPort": 3000
                }
                """.trimIndent(),
            )

        assertEquals("http://example", decoded.values.endpoint)
        assertFalse(decoded.migratedLegacyEndpoint)
    }

    @Test
    fun `migrates legacy webhooks config to single endpoint`() {
        val decoded =
            decodeConfigValues(
                json,
                """
                {
                  "botName": "Iris",
                  "webhooks": {
                    "hololive": "http://legacy"
                  }
                }
                """.trimIndent(),
            )

        assertEquals("http://legacy", decoded.values.endpoint)
        assertTrue(decoded.migratedLegacyEndpoint)
    }

    @Test
    fun `returns null when no legacy endpoint exists`() {
        val legacyJson =
            Json.parseToJsonElement(
                """
                {
                  "webhooks": {
                    "hololive": ""
                  }
                }
                """.trimIndent(),
            )
        val legacyRoot = legacyJson.jsonObject
        val legacyEndpoint =
            extractLegacyEndpoint(legacyRoot)

        assertNull(legacyEndpoint)
    }
}
