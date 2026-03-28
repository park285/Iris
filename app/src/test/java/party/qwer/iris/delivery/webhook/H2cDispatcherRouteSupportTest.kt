package party.qwer.iris.delivery.webhook

import party.qwer.iris.CommandParser
import party.qwer.iris.DEFAULT_WEBHOOK_ROUTE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class H2cDispatcherRouteSupportTest {
    private val customRouteConfig =
        object : party.qwer.iris.ConfigProvider {
            override val botId = 0L
            override val botName = ""
            override val botSocketPort = 0
            override val botToken = ""
            override val webhookToken = ""
            override val dbPollingRate = 0L
            override val messageSendRate = 0L
            override val messageSendJitterMax = 0L

            override fun webhookEndpointFor(route: String): String = ""

            override fun commandRoutePrefixes(): Map<String, List<String>> =
                mapOf(
                    "custom" to listOf("!ping"),
                )

            override fun imageMessageTypeRoutes(): Map<String, List<String>> =
                mapOf(
                    "images" to listOf("2"),
                )
        }

    @Test
    fun `routes chatbotgo commands to chatbotgo`() {
        assertEquals("chatbotgo", resolveWebhookRoute("!질문 hello"))
        assertEquals("chatbotgo", resolveWebhookRoute("!이미지 이 사진 뭐야"))
        assertEquals("chatbotgo", resolveWebhookRoute("!그림 귀여운 고양이"))
        assertEquals("chatbotgo", resolveWebhookRoute("!리셋"))
        assertEquals("chatbotgo", resolveWebhookRoute("!관리자 상태"))
        assertEquals("chatbotgo", resolveWebhookRoute("!한강"))
    }

    @Test
    fun `routes settlement commands to settlement`() {
        assertEquals("settlement", resolveWebhookRoute("!정산"))
        assertEquals("settlement", resolveWebhookRoute("!정산완료"))
        assertEquals("settlement", resolveWebhookRoute("!정산 완료"))
    }

    @Test
    fun `keeps other webhook commands on default`() {
        assertEquals(DEFAULT_WEBHOOK_ROUTE, resolveWebhookRoute("!ping"))
        assertEquals(DEFAULT_WEBHOOK_ROUTE, resolveWebhookRoute("/ping"))
    }

    @Test
    fun `returns null for non webhook messages`() {
        assertNull(resolveWebhookRoute("hello"))
    }

    @Test
    fun `config driven route prefixes override default matching`() {
        assertEquals("custom", resolveWebhookRoute(CommandParser.parse("!ping"), customRouteConfig))
    }

    @Test
    fun `routes image messages to chatbotgo`() {
        assertEquals("chatbotgo", resolveImageRoute("2"))
        assertEquals("chatbotgo", resolveImageRoute(" 2 "))
    }

    @Test
    fun `returns null for non image message types`() {
        assertNull(resolveImageRoute("1"))
        assertNull(resolveImageRoute("26"))
        assertNull(resolveImageRoute(null))
        assertNull(resolveImageRoute(""))
    }

    @Test
    fun `config driven image routes can override defaults`() {
        assertEquals("images", resolveImageRoute("2", customRouteConfig))
    }

    @Test
    fun `creates isolated dispatcher state per route`() {
        val registry = RouteDispatchRegistry { route -> TestState(route) }

        val defaultA = registry.get(DEFAULT_WEBHOOK_ROUTE)
        val defaultB = registry.get(DEFAULT_WEBHOOK_ROUTE)
        val chatbotgo = registry.get("chatbotgo")

        assertSame(defaultA, defaultB)
        assertEquals(DEFAULT_WEBHOOK_ROUTE, defaultA.route)
        assertEquals("chatbotgo", chatbotgo.route)
    }

    private data class TestState(
        val route: String,
    )
}
