package party.qwer.iris.delivery.webhook

import kotlinx.serialization.json.JsonPrimitive
import party.qwer.iris.CHATBOTGO_ROUTE
import party.qwer.iris.CommandParser
import party.qwer.iris.DEFAULT_COMMAND_ROUTE_PREFIXES
import party.qwer.iris.DEFAULT_EVENT_TYPE_ROUTES
import party.qwer.iris.DEFAULT_IMAGE_MESSAGE_TYPE_ROUTES
import party.qwer.iris.DEFAULT_WEBHOOK_ROUTE
import party.qwer.iris.SETTLEMENT_ROUTE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class H2cDispatcherRouteSupportTest {
    private val seededRouteConfig =
        TestConfigProvider(
            commandRoutes = DEFAULT_COMMAND_ROUTE_PREFIXES,
            imageRoutes = DEFAULT_IMAGE_MESSAGE_TYPE_ROUTES,
            eventRoutes = DEFAULT_EVENT_TYPE_ROUTES,
        )

    private val emptyRouteConfig = TestConfigProvider()

    @Test
    fun `seeded config routes configured webhook commands`() {
        assertEquals(CHATBOTGO_ROUTE, resolveWebhookRoute(CommandParser.parse("!질문 hello"), seededRouteConfig))
        assertEquals(CHATBOTGO_ROUTE, resolveWebhookRoute(CommandParser.parse("!프로필 테스트"), seededRouteConfig))
        assertEquals(SETTLEMENT_ROUTE, resolveWebhookRoute(CommandParser.parse("!정산"), seededRouteConfig))
    }

    @Test
    fun `seeded config routes configured image types`() {
        assertEquals(CHATBOTGO_ROUTE, resolveImageRoute("2", seededRouteConfig))
    }

    @Test
    fun `member identity events route to chatbotgo`() {
        assertEquals(CHATBOTGO_ROUTE, resolveEventRoute("nickname_change"))
        assertEquals(CHATBOTGO_ROUTE, resolveEventRoute("profile_change"))
    }

    @Test
    fun `configured event types route through config`() {
        val config = TestConfigProvider(eventRoutes = mapOf("custom-events" to listOf("role_change")))

        assertEquals("custom-events", resolveEventRoute("role_change", config))
        assertNull(resolveEventRoute("nickname_change", emptyRouteConfig))
    }

    @Test
    fun `outbox delivery resolves member identity events to chatbotgo`() {
        val resolved =
            resolveWebhookDelivery(
                command =
                    RoutingCommand(
                        text = """{"type":"nickname_change"}""",
                        room = "18219201472247343",
                        sender = "iris-system",
                        userId = "0",
                        sourceLogId = -1L,
                        messageType = "nickname_change",
                    ),
                config = seededRouteConfig.copy(webhookEndpoint = "http://127.0.0.1:31001/webhook/iris"),
            )

        assertEquals("chatbotgo", resolved?.route)
    }

    @Test
    fun `system event message id changes when nickname payload changes`() {
        val first =
            buildRoutingMessageId(
                RoutingCommand(
                    text = """{"type":"nickname_change"}""",
                    room = "18219201472247343",
                    sender = "iris-system",
                    userId = "0",
                    sourceLogId = -1L,
                    messageType = "nickname_change",
                    eventPayload = JsonPrimitive("""{"oldNickname":"카푸치노","newNickname":"졸려어"}"""),
                ),
                "chatbotgo",
            )
        val second =
            buildRoutingMessageId(
                RoutingCommand(
                    text = """{"type":"nickname_change"}""",
                    room = "18219201472247343",
                    sender = "iris-system",
                    userId = "0",
                    sourceLogId = -1L,
                    messageType = "nickname_change",
                    eventPayload = JsonPrimitive("""{"oldNickname":"졸려어","newNickname":"카푸치노"}"""),
                ),
                "chatbotgo",
            )

        assertNotEquals(first, second)
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
    fun `returns null for unrelated event types`() {
        assertNull(resolveEventRoute(null))
        assertNull(resolveEventRoute(""))
        assertNull(resolveEventRoute("member_event"))
        assertNull(resolveEventRoute("role_change"))
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
        val eventRoutes: Map<String, List<String>> = emptyMap(),
        val webhookEndpoint: String = "",
    ) : party.qwer.iris.ConfigProvider {
        override val botId: Long = 0L
        override val botName: String = ""
        override val botSocketPort: Int = 0
        override val inboundSigningSecret: String = ""
        override val outboundWebhookToken: String = ""
        override val botControlToken: String = ""
        override val dbPollingRate: Long = 0L
        override val messageSendRate: Long = 0L
        override val messageSendJitterMax: Long = 0L

        override fun webhookEndpointFor(route: String): String = webhookEndpoint

        override fun commandRoutePrefixes(): Map<String, List<String>> = commandRoutes

        override fun imageMessageTypeRoutes(): Map<String, List<String>> = imageRoutes

        override fun eventTypeRoutes(): Map<String, List<String>> = eventRoutes
    }
}
