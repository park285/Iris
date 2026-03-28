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
    fun `reads default webhook config as endpoint`() {
        val decoded =
            decodeConfigValues(
                json,
                """
                {
                  "botName": "Iris",
                  "webhooks": {
                    "default": "http://default"
                  }
                }
                """.trimIndent(),
            )

        assertEquals("http://default", decoded.values.endpoint)
        assertEquals("http://default", decoded.values.webhooks[DEFAULT_WEBHOOK_ROUTE])
        assertTrue(decoded.migratedLegacyEndpoint)
    }

    @Test
    fun `preserves multiple webhook routes while deriving default endpoint from default route`() {
        val decoded =
            decodeConfigValues(
                json,
                """
                {
                  "botName": "Iris",
                  "webhooks": {
                    "default": "http://default",
                    "chatbotgo": "http://chatbotgo"
                  }
                }
                """.trimIndent(),
            )

        assertEquals("http://default", decoded.values.endpoint)
        assertEquals("http://default", decoded.values.webhooks[DEFAULT_WEBHOOK_ROUTE])
        assertEquals("http://chatbotgo", decoded.values.webhooks["chatbotgo"])
        assertTrue(decoded.migratedLegacyEndpoint)
    }

    @Test
    fun `seeds routing defaults when routing fields are missing`() {
        val decoded =
            decodeConfigValues(
                json,
                """
                {
                  "botName": "Iris",
                  "endpoint": "http://example"
                }
                """.trimIndent(),
            )

        assertEquals(DEFAULT_COMMAND_ROUTE_PREFIXES, decoded.values.commandRoutePrefixes)
        assertEquals(DEFAULT_IMAGE_MESSAGE_TYPE_ROUTES, decoded.values.imageMessageTypeRoutes)
    }

    @Test
    fun `seeds routing defaults when routing fields are empty maps`() {
        val decoded =
            decodeConfigValues(
                json,
                """
                {
                  "botName": "Iris",
                  "endpoint": "http://example",
                  "commandRoutePrefixes": {},
                  "imageMessageTypeRoutes": {}
                }
                """.trimIndent(),
            )

        assertEquals(DEFAULT_COMMAND_ROUTE_PREFIXES, decoded.values.commandRoutePrefixes)
        assertEquals(DEFAULT_IMAGE_MESSAGE_TYPE_ROUTES, decoded.values.imageMessageTypeRoutes)
    }

    @Test
    fun `returns null when no legacy endpoint exists`() {
        val legacyJson =
            Json.parseToJsonElement(
                """
                {
                  "webhooks": {
                    "default": ""
                  }
                }
                """.trimIndent(),
            )
        val legacyRoot = legacyJson.jsonObject
        val legacyEndpoint =
            extractLegacyEndpoint(legacyRoot)

        assertNull(legacyEndpoint)
    }

    @Test
    fun `keeps default route name unchanged`() {
        assertEquals(DEFAULT_WEBHOOK_ROUTE, canonicalWebhookRoute("default"))
    }
}
