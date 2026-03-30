package party.qwer.iris

import party.qwer.iris.model.ConfigRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class ConfigUpdateOutcomeTest {
    @Test
    fun `sendrate update remains hot applied without restart`() {
        val configManager = ConfigManager(configPath = "/tmp/iris-config-update-outcome-test.json")

        val outcome =
            applyConfigUpdate(
                configManager = configManager,
                name = "sendrate",
                request = ConfigRequest(rate = 25),
            )

        assertEquals(25L, configManager.messageSendRate)
        assertEquals("sendrate", outcome.name)
        assertEquals(true, outcome.applied)
        assertFalse(outcome.requiresRestart)
    }

    @Test
    fun `unknown config name throws ApiRequestException`() {
        val configManager = ConfigManager(configPath = "/tmp/iris-config-update-outcome-test.json")

        val exception =
            assertFailsWith<ApiRequestException> {
                applyConfigUpdate(
                    configManager = configManager,
                    name = "nonexistent",
                    request = ConfigRequest(),
                )
            }

        assertEquals("unknown config 'nonexistent'", exception.message)
    }
}
