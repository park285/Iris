package party.qwer.iris.delivery.webhook

import party.qwer.iris.CommandParser
import party.qwer.iris.DEFAULT_WEBHOOK_ROUTE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class H2cDispatcherRouteSupportTest {
    private val seededRouteConfig =
        TestConfigProvider(
            commandRoutes =
                mapOf(
                    "settlement" to listOf("!정산", "!정산완료"),
                    "chatbotgo" to listOf("!질문", "!이미지", "!그림", "!리셋", "!관리자", "!한강"),
                ),
            imageRoutes =
                mapOf(
                    "chatbotgo" to listOf("2"),
                ),
        )

    private val emptyRouteConfig = TestConfigProvider()

    @Test
    fun `seeded config routes configured webhook commands`() {
        assertEquals("chatbotgo", resolveWebhookRoute(CommandParser.parse("!질문 hello"), seededRouteConfig))
        assertEquals("settlement", resolveWebhookRoute(CommandParser.parse("!정산"), seededRouteConfig))
    }

    @Test
    fun `seeded config routes configured image types`() {
        assertEquals("chatbotgo", resolveImageRoute("2", seededRouteConfig))
    }

    @Test
    fun `seeded config falls back to default route when webhook command does not match`() {
        assertEquals(DEFAULT_WEBHOOK_ROUTE, resolveWebhookRoute(CommandParser.parse("!ping"), seededRouteConfig))
    }

    @Test
    fun `seeded config returns null when image type does not match`() {
        assertNull(resolveImageRoute("26", seededRouteConfig))
    }

    @Test
    fun `empty config falls back to default webhook route and no image route`() {
        assertEquals(DEFAULT_WEBHOOK_ROUTE, resolveWebhookRoute(CommandParser.parse("!질문 hello"), emptyRouteConfig))
        assertEquals(DEFAULT_WEBHOOK_ROUTE, resolveWebhookRoute(CommandParser.parse("!정산"), emptyRouteConfig))
        assertNull(resolveImageRoute("2", emptyRouteConfig))
    }

    @Test
    fun `no config overload falls back to default webhook route and no image route`() {
        assertEquals(DEFAULT_WEBHOOK_ROUTE, resolveWebhookRoute("!질문 hello"))
        assertEquals(DEFAULT_WEBHOOK_ROUTE, resolveWebhookRoute("!정산"))
        assertNull(resolveImageRoute("2"))
    }

    @Test
    fun `returns null for non webhook messages`() {
        assertNull(resolveWebhookRoute("hello"))
    }

    @Test
    fun `returns null for blank or missing image message types`() {
        assertNull(resolveImageRoute(null))
        assertNull(resolveImageRoute(""))
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

    private data class TestConfigProvider(
        val commandRoutes: Map<String, List<String>> = emptyMap(),
        val imageRoutes: Map<String, List<String>> = emptyMap(),
    ) : party.qwer.iris.ConfigProvider {
        override val botId: Long = 0L
        override val botName: String = ""
        override val botSocketPort: Int = 0
        override val botToken: String = ""
        override val webhookToken: String = ""
        override val dbPollingRate: Long = 0L
        override val messageSendRate: Long = 0L
        override val messageSendJitterMax: Long = 0L

        override fun webhookEndpointFor(route: String): String = ""

        override fun commandRoutePrefixes(): Map<String, List<String>> = commandRoutes

        override fun imageMessageTypeRoutes(): Map<String, List<String>> = imageRoutes
    }
}
