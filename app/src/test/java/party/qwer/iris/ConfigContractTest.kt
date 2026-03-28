package party.qwer.iris

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import party.qwer.iris.model.ConfigValues
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfigContractTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun `config response exposes only strict nested contract`() {
        val response =
            buildConfigResponse(
                snapshot =
                    ConfigValues(
                        botHttpPort = 4000,
                        webhooks = mapOf(DEFAULT_WEBHOOK_ROUTE to "http://snapshot-hololive", "chatbotgo" to "http://snapshot-chatbotgo"),
                        dbPollingRate = 100,
                        messageSendRate = 50,
                    ),
                effective =
                    ConfigValues(
                        botHttpPort = 3000,
                        webhooks = mapOf(DEFAULT_WEBHOOK_ROUTE to "http://effective-hololive", "chatbotgo" to "http://effective-chatbotgo"),
                        dbPollingRate = 100,
                        messageSendRate = 50,
                    ),
            )
        val encoded = json.encodeToString(response)

        assertEquals(4000, response.user.botHttpPort)
        assertEquals(3000, response.applied.botHttpPort)
        assertEquals(0L, response.discovered.botId)
        assertTrue(response.pendingRestart.required)
        assertEquals(listOf("bot_http_port"), response.pendingRestart.fields)
        assertEquals("http://snapshot-hololive", response.user.webhooks[DEFAULT_WEBHOOK_ROUTE])
        assertEquals("http://effective-chatbotgo", response.applied.webhooks["chatbotgo"])
        assertFalse(encoded.contains("\"snapshot\""))
        assertFalse(encoded.contains("\"effective\""))
    }

    @Test
    fun `config update response exposes only strict nested contract`() {
        val response =
            buildConfigUpdateResponse(
                status =
                    ConfigUpdateStatus(
                        name = "endpoint",
                        persisted = true,
                        applied = true,
                        requiresRestart = false,
                    ),
                snapshot =
                    ConfigValues(
                        botHttpPort = 4000,
                        endpoint = "http://example",
                        webhooks = mapOf(DEFAULT_WEBHOOK_ROUTE to "http://example", "chatbotgo" to "http://chatbotgo"),
                    ),
                effective =
                    ConfigValues(
                        botHttpPort = 3000,
                        endpoint = "http://example",
                        webhooks = mapOf(DEFAULT_WEBHOOK_ROUTE to "http://example", "chatbotgo" to "http://chatbotgo"),
                    ),
            )
        val encoded = json.encodeToString(response)

        assertFalse(response.requiresRestart)
        assertTrue(response.pendingRestart.required)
        assertEquals(listOf("bot_http_port"), response.pendingRestart.fields)
        assertEquals("http://chatbotgo", response.runtimeApplied.webhooks["chatbotgo"])
        assertFalse(encoded.contains("\"snapshot\""))
        assertFalse(encoded.contains("\"effective\""))
    }
}
