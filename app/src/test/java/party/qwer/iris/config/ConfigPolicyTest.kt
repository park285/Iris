package party.qwer.iris.config

import party.qwer.iris.DEFAULT_COMMAND_ROUTE_PREFIXES
import party.qwer.iris.DEFAULT_EVENT_TYPE_ROUTES
import party.qwer.iris.DEFAULT_IMAGE_MESSAGE_TYPE_ROUTES
import party.qwer.iris.UserConfigState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfigPolicyTest {
    @Test
    fun `BOT_SOCKET_PORT requires restart`() {
        assertTrue(ConfigPolicy.requiresRestart(ConfigField.BOT_SOCKET_PORT))
    }

    @Test
    fun `BOT_NAME does not require restart`() {
        assertFalse(ConfigPolicy.requiresRestart(ConfigField.BOT_NAME))
    }

    @Test
    fun `DB_POLLING_RATE does not require restart`() {
        assertFalse(ConfigPolicy.requiresRestart(ConfigField.DB_POLLING_RATE))
    }

    @Test
    fun `MESSAGE_SEND_RATE does not require restart`() {
        assertFalse(ConfigPolicy.requiresRestart(ConfigField.MESSAGE_SEND_RATE))
    }

    @Test
    fun `MESSAGE_SEND_JITTER_MAX does not require restart`() {
        assertFalse(ConfigPolicy.requiresRestart(ConfigField.MESSAGE_SEND_JITTER_MAX))
    }

    @Test
    fun `ROUTING_POLICY does not require restart`() {
        assertFalse(ConfigPolicy.requiresRestart(ConfigField.ROUTING_POLICY))
    }

    @Test
    fun `INBOUND_SIGNING_SECRET requires restart`() {
        assertTrue(ConfigPolicy.requiresRestart(ConfigField.INBOUND_SIGNING_SECRET))
    }

    @Test
    fun `OUTBOUND_WEBHOOK_TOKEN requires restart`() {
        assertTrue(ConfigPolicy.requiresRestart(ConfigField.OUTBOUND_WEBHOOK_TOKEN))
    }

    @Test
    fun `BOT_CONTROL_TOKEN requires restart`() {
        assertTrue(ConfigPolicy.requiresRestart(ConfigField.BOT_CONTROL_TOKEN))
    }

    @Test
    fun `validate returns empty for valid default state`() {
        val state = UserConfigState()
        val errors = ConfigPolicy.validate(state)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `validate rejects negative dbPollingRate`() {
        val state = UserConfigState(dbPollingRate = -1)
        val errors = ConfigPolicy.validate(state)
        assertEquals(1, errors.size)
        assertEquals(ConfigField.DB_POLLING_RATE, errors[0].field)
    }

    @Test
    fun `validate rejects zero dbPollingRate`() {
        val state = UserConfigState(dbPollingRate = 0)
        val errors = ConfigPolicy.validate(state)
        assertEquals(1, errors.size)
        assertEquals(ConfigField.DB_POLLING_RATE, errors[0].field)
    }

    @Test
    fun `validate rejects negative messageSendRate`() {
        val state = UserConfigState(messageSendRate = -1)
        val errors = ConfigPolicy.validate(state)
        assertEquals(1, errors.size)
        assertEquals(ConfigField.MESSAGE_SEND_RATE, errors[0].field)
    }

    @Test
    fun `validate accepts zero messageSendRate`() {
        val state = UserConfigState(messageSendRate = 0)
        val errors = ConfigPolicy.validate(state)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `validate rejects negative messageSendJitterMax`() {
        val state = UserConfigState(messageSendJitterMax = -1)
        val errors = ConfigPolicy.validate(state)
        assertEquals(1, errors.size)
        assertEquals(ConfigField.MESSAGE_SEND_JITTER_MAX, errors[0].field)
    }

    @Test
    fun `validate rejects invalid port zero`() {
        val state = UserConfigState(botHttpPort = 0)
        val errors = ConfigPolicy.validate(state)
        assertEquals(1, errors.size)
        assertEquals(ConfigField.BOT_SOCKET_PORT, errors[0].field)
    }

    @Test
    fun `validate rejects port above 65535`() {
        val state = UserConfigState(botHttpPort = 70000)
        val errors = ConfigPolicy.validate(state)
        assertEquals(1, errors.size)
        assertEquals(ConfigField.BOT_SOCKET_PORT, errors[0].field)
    }

    @Test
    fun `validate collects multiple errors`() {
        val state =
            UserConfigState(
                botHttpPort = 0,
                dbPollingRate = -1,
                messageSendRate = -5,
            )
        val errors = ConfigPolicy.validate(state)
        assertEquals(3, errors.size)
        val fields = errors.map { it.field }.toSet()
        assertTrue(ConfigField.BOT_SOCKET_PORT in fields)
        assertTrue(ConfigField.DB_POLLING_RATE in fields)
        assertTrue(ConfigField.MESSAGE_SEND_RATE in fields)
    }

    @Test
    fun `validate rejects invalid webhook endpoint scheme`() {
        val state = UserConfigState(endpoint = "ftp://example.com/webhook")

        val errors = ConfigPolicy.validate(state)

        assertTrue(errors.any { it.field == ConfigField.ROUTING_POLICY && it.message.contains("endpoint") })
    }

    @Test
    fun `validate rejects placeholder webhook endpoint`() {
        val state = UserConfigState(webhooks = mapOf("chatbotgo" to "http://example.invalid/webhook"))

        val errors = ConfigPolicy.validate(state)

        assertTrue(errors.any { it.field == ConfigField.ROUTING_POLICY && it.message.contains("placeholder") })
    }

    @Test
    fun `validate rejects unresolved webhook endpoint placeholder`() {
        val state = UserConfigState(endpoint = "\${IRIS_WEBHOOK_CHATBOTGO}")

        val errors = ConfigPolicy.validate(state)

        assertTrue(errors.any { it.field == ConfigField.ROUTING_POLICY && it.message.contains("placeholder") })
    }

    @Test
    fun `validate rejects invalid webhook route key`() {
        val state = UserConfigState(webhooks = mapOf("bad route" to "https://example.com/webhook"))

        val errors = ConfigPolicy.validate(state)

        assertTrue(errors.any { it.field == ConfigField.ROUTING_POLICY && it.message.contains("webhooks.bad route") })
    }

    @Test
    fun `validate rejects secrets with surrounding whitespace`() {
        val state = UserConfigState(inboundSigningSecret = " inbound-secret ")

        val errors = ConfigPolicy.validate(state)

        assertTrue(errors.any { it.field == ConfigField.INBOUND_SIGNING_SECRET })
    }

    @Test
    fun `validate rejects placeholder secrets`() {
        val state = UserConfigState(inboundSigningSecret = "change-me")

        val errors = ConfigPolicy.validate(state)

        assertTrue(errors.any { it.field == ConfigField.INBOUND_SIGNING_SECRET && it.message.contains("placeholder") })
    }

    @Test
    fun `default routing policy seeds chatbotgo routes and requires external bootstrap`() {
        val policy = ConfigPolicy.defaultRoutingPolicy
        assertEquals(DEFAULT_COMMAND_ROUTE_PREFIXES, policy.commandRoutePrefixes)
        assertEquals(DEFAULT_IMAGE_MESSAGE_TYPE_ROUTES, policy.imageMessageTypeRoutes)
        assertEquals(DEFAULT_EVENT_TYPE_ROUTES, policy.eventTypeRoutes)
        assertTrue(policy.requiresExternalBootstrap)
    }
}
