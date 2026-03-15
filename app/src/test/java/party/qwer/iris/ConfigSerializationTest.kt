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
        assertEquals("http://legacy", decoded.values.webhooks["hololive"])
        assertTrue(decoded.migratedLegacyEndpoint)
    }

    @Test
    fun `preserves multiple webhook routes while deriving default endpoint from hololive`() {
        val decoded =
            decodeConfigValues(
                json,
                """
                {
                  "botName": "Iris",
                  "webhooks": {
                    "hololive": "http://hololive",
                    "chatbotgo": "http://chatbotgo"
                  }
                }
                """.trimIndent(),
            )

        assertEquals("http://hololive", decoded.values.endpoint)
        assertEquals("http://hololive", decoded.values.webhooks["hololive"])
        assertEquals("http://chatbotgo", decoded.values.webhooks["chatbotgo"])
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
