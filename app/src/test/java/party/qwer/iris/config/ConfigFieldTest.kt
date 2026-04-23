package party.qwer.iris.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConfigFieldTest {
    @Test
    fun `enum contains all expected config fields`() {
        val expected =
            setOf(
                "BOT_NAME",
                "BOT_SOCKET_PORT",
                "DB_POLLING_RATE",
                "MESSAGE_SEND_RATE",
                "MESSAGE_SEND_JITTER_MAX",
                "ROUTING_POLICY",
                "INBOUND_SIGNING_SECRET",
                "OUTBOUND_WEBHOOK_TOKEN",
                "BOT_CONTROL_TOKEN",
                "BRIDGE_TOKEN",
            )
        val actual = ConfigField.entries.map { it.name }.toSet()
        assertEquals(expected, actual)
    }

    @Test
    fun `entries count matches expected`() {
        assertEquals(10, ConfigField.entries.size)
    }

    @Test
    fun `valueOf round-trips for all entries`() {
        for (field in ConfigField.entries) {
            assertEquals(field, ConfigField.valueOf(field.name))
        }
    }

    @Test
    fun `BOT_SOCKET_PORT is distinct from BOT_NAME`() {
        assertTrue(ConfigField.BOT_SOCKET_PORT != ConfigField.BOT_NAME)
    }
}
