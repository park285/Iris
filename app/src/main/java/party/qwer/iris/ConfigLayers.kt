package party.qwer.iris

import party.qwer.iris.model.ConfigValues
import party.qwer.iris.model.UserConfigValues

internal data class UserConfigState(
    val botName: String = "Iris",
    val botHttpPort: Int = 3000,
    val endpoint: String = "",
    val webhooks: Map<String, String> = emptyMap(),
    val inboundSigningSecret: String = "",
    val outboundWebhookToken: String = "",
    val botControlToken: String = "",
    val dbPollingRate: Long = 100,
    val messageSendRate: Long = 50,
    val messageSendJitterMax: Long = 0,
    val commandRoutePrefixes: Map<String, List<String>> = emptyMap(),
    val imageMessageTypeRoutes: Map<String, List<String>> = emptyMap(),
)

internal data class DiscoveredConfigState(
    val botId: Long = 0L,
)

internal data class AppliedConfigState(
    val user: UserConfigState = UserConfigState(),
    val discovered: DiscoveredConfigState = DiscoveredConfigState(),
) {
    fun toLegacyConfigValues(): ConfigValues =
        ConfigValues(
            botName = user.botName,
            botHttpPort = user.botHttpPort,
            endpoint = user.endpoint,
            webhooks = user.webhooks,
            inboundSigningSecret = user.inboundSigningSecret,
            outboundWebhookToken = user.outboundWebhookToken,
            botControlToken = user.botControlToken,
            dbPollingRate = user.dbPollingRate,
            messageSendRate = user.messageSendRate,
            messageSendJitterMax = user.messageSendJitterMax,
            commandRoutePrefixes = user.commandRoutePrefixes,
            imageMessageTypeRoutes = user.imageMessageTypeRoutes,
            botId = discovered.botId,
        )
}

internal fun ConfigValues.toUserConfigState(): UserConfigState =
    UserConfigState(
        botName = botName,
        botHttpPort = botHttpPort,
        endpoint = endpoint,
        webhooks = webhooks,
        inboundSigningSecret = inboundSigningSecret,
        outboundWebhookToken = outboundWebhookToken,
        botControlToken = botControlToken,
        dbPollingRate = dbPollingRate,
        messageSendRate = messageSendRate,
        messageSendJitterMax = messageSendJitterMax,
        commandRoutePrefixes = commandRoutePrefixes,
        imageMessageTypeRoutes = imageMessageTypeRoutes,
    )

internal fun ConfigValues.toDiscoveredConfigState(): DiscoveredConfigState =
    DiscoveredConfigState(
        botId = botId,
    )

internal fun UserConfigState.toPersistedConfigValues(): UserConfigValues =
    UserConfigValues(
        botName = botName,
        botHttpPort = botHttpPort,
        endpoint = endpoint,
        webhooks = webhooks,
        inboundSigningSecret = inboundSigningSecret,
        outboundWebhookToken = outboundWebhookToken,
        botControlToken = botControlToken,
        dbPollingRate = dbPollingRate,
        messageSendRate = messageSendRate,
        messageSendJitterMax = messageSendJitterMax,
        commandRoutePrefixes = commandRoutePrefixes,
        imageMessageTypeRoutes = imageMessageTypeRoutes,
    )

internal fun UserConfigState.toLegacyConfigValues(
    botId: Long = 0L,
): ConfigValues =
    ConfigValues(
        botName = botName,
        botHttpPort = botHttpPort,
        endpoint = endpoint,
        webhooks = webhooks,
        inboundSigningSecret = inboundSigningSecret,
        outboundWebhookToken = outboundWebhookToken,
        botControlToken = botControlToken,
        dbPollingRate = dbPollingRate,
        messageSendRate = messageSendRate,
        messageSendJitterMax = messageSendJitterMax,
        commandRoutePrefixes = commandRoutePrefixes,
        imageMessageTypeRoutes = imageMessageTypeRoutes,
        botId = botId,
    )

internal data class ConfigRuntimeState(
    val snapshotUser: UserConfigState = UserConfigState(),
    val appliedUser: UserConfigState = UserConfigState(),
    val discovered: DiscoveredConfigState = DiscoveredConfigState(),
    val isDirty: Boolean = false,
)
