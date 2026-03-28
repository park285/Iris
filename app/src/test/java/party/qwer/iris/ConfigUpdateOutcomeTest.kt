package party.qwer.iris

import party.qwer.iris.model.ConfigRequest
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
