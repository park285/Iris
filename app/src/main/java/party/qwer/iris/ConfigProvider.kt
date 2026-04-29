package party.qwer.iris

interface BotIdentityProvider {
    val botId: Long
}

interface BotConfigProvider {
    val botName: String
    val botSocketPort: Int
}

interface ActiveSecretProvider {
    fun activeInboundSigningSecret(): String

    fun activeOutboundWebhookToken(): String

    fun activeBotControlToken(): String
}

interface SecretSnapshotProvider : ActiveSecretProvider {
    val inboundSigningSecret: String
    val outboundWebhookToken: String
    val botControlToken: String
}

interface PollingConfigProvider {
    val dbPollingRate: Long
}

interface ReplyDispatchConfigProvider {
    val messageSendRate: Long
    val messageSendJitterMax: Long
}

interface WebhookRoutingConfigProvider {
    fun webhookEndpointFor(route: String): String

    fun commandRoutePrefixes(): Map<String, List<String>> = emptyMap()

    fun imageMessageTypeRoutes(): Map<String, List<String>> = emptyMap()

    fun eventTypeRoutes(): Map<String, List<String>> = emptyMap()
}

interface ConfigProvider :
    BotIdentityProvider,
    BotConfigProvider,
    SecretSnapshotProvider,
    PollingConfigProvider,
    ReplyDispatchConfigProvider,
    WebhookRoutingConfigProvider {
    override fun activeInboundSigningSecret(): String = inboundSigningSecret

    override fun activeOutboundWebhookToken(): String = outboundWebhookToken

    override fun activeBotControlToken(): String = botControlToken
}
