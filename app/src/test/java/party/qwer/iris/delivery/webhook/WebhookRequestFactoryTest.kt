package party.qwer.iris.delivery.webhook

import okhttp3.RequestBody
import okio.Buffer
import party.qwer.iris.ConfigProvider
import party.qwer.iris.DEFAULT_WEBHOOK_ROUTE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WebhookRequestFactoryTest {
    @Test
    fun `creates json post request with iris headers and token`() {
        val factory = WebhookRequestFactory(config = TestConfigProvider(webhookToken = "secret-token"))
        val delivery =
            WebhookDelivery(
                url = "http://127.0.0.1:18080/webhook/iris",
                messageId = "kakao-log-42-default",
                route = DEFAULT_WEBHOOK_ROUTE,
                payloadJson = """{"message":"hello"}""",
            )

        val request = factory.create(delivery)

        assertEquals(delivery.url, request.url.toString())
        assertEquals("POST", request.method)
        assertEquals("secret-token", request.header("X-Iris-Token"))
        assertEquals(delivery.messageId, request.header("X-Iris-Message-Id"))
        assertEquals(delivery.route, request.header("X-Iris-Route"))
        assertEquals("application/json; charset=utf-8", requireNotNull(request.body).contentType().toString())
        assertEquals(delivery.payloadJson, request.body.readUtf8())
    }

    @Test
    fun `omits iris token header when webhook token is blank`() {
        val factory = WebhookRequestFactory(config = TestConfigProvider(webhookToken = ""))
        val delivery =
            WebhookDelivery(
                url = "http://127.0.0.1:18080/webhook/iris",
                messageId = "kakao-log-43-default",
                route = DEFAULT_WEBHOOK_ROUTE,
                payloadJson = """{"message":"hello"}""",
            )

        val request = factory.create(delivery)

        assertNull(request.header("X-Iris-Token"))
    }

    private fun RequestBody?.readUtf8(): String {
        val buffer = Buffer()
        requireNotNull(this).writeTo(buffer)
        return buffer.readUtf8()
    }

    private data class TestConfigProvider(
        override val webhookToken: String,
    ) : ConfigProvider {
        override val botId: Long = 0L
        override val botName: String = "iris"
        override val botSocketPort: Int = 0
        override val botToken: String = ""
        override val inboundSigningSecret: String = ""
        override val outboundWebhookToken: String = ""
        override val botControlToken: String = ""
        override val dbPollingRate: Long = 0L
        override val messageSendRate: Long = 0L
        override val messageSendJitterMax: Long = 0L

        override fun webhookEndpointFor(route: String): String = ""
    }
}
