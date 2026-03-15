package party.qwer.iris

import party.qwer.iris.model.ConfigValues
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfigContractTest {
    @Test
    fun `config response exposes pending restart when snapshot diverges from effective`() {
        val response =
            buildConfigResponse(
                snapshot =
                    ConfigValues(
                        botHttpPort = 4000,
                        webhooks = mapOf("hololive" to "http://snapshot-hololive", "chatbotgo" to "http://snapshot-chatbotgo"),
                        dbPollingRate = 100,
                        messageSendRate = 50,
                    ),
                effective =
                    ConfigValues(
                        botHttpPort = 3000,
                        webhooks = mapOf("hololive" to "http://effective-hololive", "chatbotgo" to "http://effective-chatbotgo"),
                        dbPollingRate = 100,
                        messageSendRate = 50,
                    ),
            )

        assertEquals(4000, response.snapshot.botHttpPort)
        assertEquals(3000, response.effective.botHttpPort)
        assertTrue(response.pendingRestart.required)
        assertEquals(listOf("bot_http_port"), response.pendingRestart.fields)
        assertEquals(3000, response.botHttpPort)
        assertEquals("http://snapshot-hololive", response.snapshot.webhooks["hololive"])
        assertEquals("http://effective-chatbotgo", response.effective.webhooks["chatbotgo"])
        assertEquals("http://effective-hololive", response.webhooks["hololive"])
    }

    @Test
    fun `config update response keeps per-update restart flag and overall pending restart state`() {
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
                        webhooks = mapOf("hololive" to "http://example", "chatbotgo" to "http://chatbotgo"),
                    ),
                effective =
                    ConfigValues(
                        botHttpPort = 3000,
                        endpoint = "http://example",
                        webhooks = mapOf("hololive" to "http://example", "chatbotgo" to "http://chatbotgo"),
                    ),
            )

        assertFalse(response.requiresRestart)
        assertTrue(response.pendingRestart.required)
        assertEquals(listOf("bot_http_port"), response.pendingRestart.fields)
        assertEquals("http://chatbotgo", response.effective.webhooks["chatbotgo"])
    }
}
