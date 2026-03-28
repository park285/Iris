package party.qwer.iris

import party.qwer.iris.model.ConfigValues
import kotlin.test.Test
import kotlin.test.assertEquals

class WebhookConfigUpdateTest {
    @Test
    fun `updates default route endpoint and keeps default webhook in sync`() {
        val updated =
            updateWebhookConfig(
                ConfigValues(
                    endpoint = "http://old-default",
                    webhooks = mapOf(DEFAULT_WEBHOOK_ROUTE to "http://old-default", "chatbotgo" to "http://chatbotgo"),
                ),
                route = DEFAULT_WEBHOOK_ROUTE,
                endpoint = "http://new-default",
            )

        assertEquals("http://new-default", updated.endpoint)
        assertEquals("http://new-default", updated.webhooks[DEFAULT_WEBHOOK_ROUTE])
        assertEquals("http://chatbotgo", updated.webhooks["chatbotgo"])
    }

    @Test
    fun `updates non default route without changing default endpoint`() {
        val updated =
            updateWebhookConfig(
                ConfigValues(
                    endpoint = "http://default",
                    webhooks = mapOf(DEFAULT_WEBHOOK_ROUTE to "http://default", "chatbotgo" to "http://old-chatbotgo"),
                ),
                route = "chatbotgo",
                endpoint = "http://new-chatbotgo",
            )

        assertEquals("http://default", updated.endpoint)
        assertEquals("http://default", updated.webhooks[DEFAULT_WEBHOOK_ROUTE])
        assertEquals("http://new-chatbotgo", updated.webhooks["chatbotgo"])
    }

    @Test
    fun `clearing non default route removes override and keeps default route`() {
        val updated =
            updateWebhookConfig(
                ConfigValues(
                    endpoint = "http://default",
                    webhooks = mapOf(DEFAULT_WEBHOOK_ROUTE to "http://default", "chatbotgo" to "http://chatbotgo"),
                ),
                route = "chatbotgo",
                endpoint = "",
            )

        assertEquals("http://default", updated.endpoint)
        assertEquals("http://default", updated.webhooks[DEFAULT_WEBHOOK_ROUTE])
        assertEquals<String?>(null, updated.webhooks["chatbotgo"])
    }
}
