package party.qwer.iris.http

import party.qwer.iris.ApiRequestException
import party.qwer.iris.ConfigManager
import party.qwer.iris.applyConfigUpdate
import party.qwer.iris.model.ConfigRequest
import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfigRoutesTest {
    @Test
    fun `rejects unknown config name`() {
        val configPath = Files.createTempFile("iris-config-routes-test", ".json")
        val configManager = ConfigManager(configPath = configPath.toString())

        val error =
            assertFailsWith<ApiRequestException> {
                applyConfigUpdate(
                    configManager = configManager,
                    name = "unknown",
                    request = ConfigRequest(endpoint = "http://example.com"),
                )
            }

        assertEquals("unknown config 'unknown'", error.message)
        configPath.deleteIfExists()
    }

    @Test
    fun `accepts valid endpoint`() {
        val configPath = Files.createTempFile("iris-config-routes-test", ".json")
        val configManager = ConfigManager(configPath = configPath.toString())

        val outcome =
            applyConfigUpdate(
                configManager = configManager,
                name = "endpoint",
                request = ConfigRequest(endpoint = "https://example.com/webhook"),
            )

        assertEquals("https://example.com/webhook", configManager.defaultWebhookEndpoint)
        assertTrue(outcome.applied)
        assertFalse(outcome.requiresRestart)
        configPath.deleteIfExists()
    }

    @Test
    fun `rejects invalid endpoint`() {
        val configPath = Files.createTempFile("iris-config-routes-test", ".json")
        val configManager = ConfigManager(configPath = configPath.toString())

        val error =
            assertFailsWith<ApiRequestException> {
                applyConfigUpdate(
                    configManager = configManager,
                    name = "endpoint",
                    request = ConfigRequest(endpoint = "ftp://example.com/webhook"),
                )
            }

        assertEquals("endpoint must start with http:// or https://", error.message)
        configPath.deleteIfExists()
    }

    @Test
    fun `botport sets requiresRestart`() {
        val configPath = Files.createTempFile("iris-config-routes-test", ".json")
        val configManager = ConfigManager(configPath = configPath.toString())
        val originalPort = configManager.botSocketPort

        val outcome =
            applyConfigUpdate(
                configManager = configManager,
                name = "botport",
                request = ConfigRequest(port = 4000),
            )

        val response = configManager.configResponse()

        assertEquals(originalPort, configManager.botSocketPort)
        assertEquals(4000, response.user.botHttpPort)
        assertEquals(originalPort, response.applied.botHttpPort)
        assertFalse(outcome.applied)
        assertTrue(outcome.requiresRestart)
        configPath.deleteIfExists()
    }
}
