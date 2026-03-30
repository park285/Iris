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
    fun `seeds legacy webhook and bot tokens into role secrets when new fields are absent`() {
        val decoded =
            decodeConfigValues(
                json,
                """
                {
                  "webhookToken": "legacy-webhook",
                  "botToken": "legacy-bot"
                }
                """.trimIndent(),
            )

        assertEquals("legacy-webhook", decoded.values.inboundSigningSecret)
        assertEquals("legacy-webhook", decoded.values.outboundWebhookToken)
        assertEquals("legacy-bot", decoded.values.botControlToken)
        assertTrue(decoded.migratedLegacySecrets)
    }

    @Test
    fun `prefers explicit role secrets over legacy tokens`() {
        val decoded =
            decodeConfigValues(
                json,
                """
                {
                  "webhookToken": "legacy-webhook",
                  "botToken": "legacy-bot",
                  "inboundSigningSecret": "explicit-inbound",
                  "outboundWebhookToken": "explicit-outbound",
                  "botControlToken": "explicit-control"
                }
                """.trimIndent(),
            )

        assertEquals("explicit-inbound", decoded.values.inboundSigningSecret)
        assertEquals("explicit-outbound", decoded.values.outboundWebhookToken)
        assertEquals("explicit-control", decoded.values.botControlToken)
        assertFalse(decoded.migratedLegacySecrets)
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
    fun `keeps routing fields empty when routing fields are missing`() {
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

        assertEquals(emptyMap(), decoded.values.commandRoutePrefixes)
        assertEquals(emptyMap(), decoded.values.imageMessageTypeRoutes)
    }

    @Test
    fun `keeps routing fields empty when routing fields are empty maps`() {
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

        assertEquals(emptyMap(), decoded.values.commandRoutePrefixes)
        assertEquals(emptyMap(), decoded.values.imageMessageTypeRoutes)
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
