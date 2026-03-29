package party.qwer.iris

import kotlinx.serialization.json.Json
import party.qwer.iris.config.ConfigPersistence
import party.qwer.iris.config.ConfigStateStore
import party.qwer.iris.model.ConfigResponse
import party.qwer.iris.model.ConfigUpdateResponse
import party.qwer.iris.model.ConfigValues

class ConfigManager(
    private val configPath: String = System.getenv("IRIS_CONFIG_PATH") ?: "/data/local/tmp/config.json",
) : ConfigProvider {
    private val json =
        Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }

    private val stateStore = ConfigStateStore()
    private val persistence = ConfigPersistence(configPath, json)

    init {
        loadConfig()
    }

    private fun loadConfig() {
        val loaded = persistence.load()
        if (loaded == null) {
            IrisLogger.info("config.json not found at $configPath, creating default config.")
            saveConfig()
            return
        }

        stateStore.mutate {
            ConfigRuntimeState(
                snapshotUser = loaded.userState,
                appliedUser = loaded.userState.copy(),
                discovered = it.discovered,
                isDirty = loaded.migratedLegacyEndpoint,
            )
        }
        IrisLogger.debug(
            "Loaded config from $configPath " +
                "(inboundSigningSecretConfigured=${stateStore.current().snapshotUser.inboundSigningSecret.isNotBlank()}, " +
                "outboundWebhookTokenConfigured=${stateStore.current().snapshotUser.outboundWebhookToken.isNotBlank()}, " +
                "botControlTokenConfigured=${stateStore.current().snapshotUser.botControlToken.isNotBlank()})",
        )
        if (loaded.migratedLegacyEndpoint) {
            IrisLogger.info("Migrated legacy webhook config to route-aware model")
        }
    }

    private fun saveConfig(): Boolean {
        val currentState = stateStore.current()
        IrisLogger.debug(
            "Saving config to $configPath " +
                "(inboundSigningSecretConfigured=${currentState.snapshotUser.inboundSigningSecret.isNotBlank()}, " +
                "outboundWebhookTokenConfigured=${currentState.snapshotUser.outboundWebhookToken.isNotBlank()}, " +
                "botControlTokenConfigured=${currentState.snapshotUser.botControlToken.isNotBlank()})",
        )
        val success = persistence.save(currentState.snapshotUser)
        if (success) {
            stateStore.clearDirty()
        }
        return success
    }

    fun saveConfigNow(): Boolean =
        synchronized(this) {
            if (!stateStore.current().isDirty) {
                return true
            }
            return saveConfig()
        }

    override var botId: Long
        get() = stateStore.current().discovered.botId
        set(value) {
            stateStore.mutate { current ->
                if (current.discovered.botId == value) {
                    current
                } else {
                    current.copy(discovered = current.discovered.copy(botId = value))
                }
            }
            IrisLogger.debug("Bot Id is updated to: $botId")
        }

    override var botName: String
        get() = stateStore.current().appliedUser.botName
        set(value) {
            stateStore.updateUserState(applyImmediately = true) { it.copy(botName = value) }
            IrisLogger.debug("Bot name updated to: $botName")
        }

    override var botSocketPort: Int
        get() = stateStore.current().appliedUser.botHttpPort
        set(value) {
            stateStore.updateUserState(applyImmediately = false) { it.copy(botHttpPort = value) }
            IrisLogger.debug(
                "Bot port snapshot updated to: ${stateStore.current().snapshotUser.botHttpPort} " +
                    "(effective=${stateStore.current().appliedUser.botHttpPort})",
            )
        }

    var defaultWebhookEndpoint: String
        get() = stateStore.current().appliedUser.endpoint
        set(value) {
            val normalized = value.trim()
            if (defaultWebhookEndpoint == normalized) {
                return
            }
            setWebhookEndpoint(DEFAULT_WEBHOOK_ROUTE, normalized)
        }

    fun setWebhookEndpoint(
        route: String,
        endpoint: String,
    ) {
        synchronized(this) {
            val normalizedRoute = route.trim()
            val normalizedEndpoint = endpoint.trim()
            if (normalizedRoute.isEmpty()) {
                return
            }

            val current = stateStore.current()
            val updatedSnapshot =
                updateWebhookConfig(
                    current.snapshotUser.toLegacyConfigValues(),
                    normalizedRoute,
                    normalizedEndpoint,
                ).toUserConfigState()
            val updatedEffective =
                updateWebhookConfig(
                    current.appliedUser.toLegacyConfigValues(),
                    normalizedRoute,
                    normalizedEndpoint,
                ).toUserConfigState()
            if (current.snapshotUser == updatedSnapshot && current.appliedUser == updatedEffective) {
                return
            }

            stateStore.mutate {
                it.copy(
                    snapshotUser = updatedSnapshot,
                    appliedUser = updatedEffective,
                    isDirty = true,
                )
            }
            if (normalizedRoute == DEFAULT_WEBHOOK_ROUTE) {
                IrisLogger.debug("Default webhook endpoint updated")
            } else {
                IrisLogger.debug("Webhook endpoint updated for route=$normalizedRoute")
            }
        }
    }

    override fun webhookEndpointFor(route: String): String =
        configuredWebhookEndpoint(stateStore.current().appliedUser.toLegacyConfigValues(), route)

    override val inboundSigningSecret: String
        get() = stateStore.current().snapshotUser.inboundSigningSecret

    override val outboundWebhookToken: String
        get() = stateStore.current().snapshotUser.outboundWebhookToken

    override val botControlToken: String
        get() = stateStore.current().snapshotUser.botControlToken

    internal fun signingSecret(): String = stateStore.current().snapshotUser.inboundSigningSecret

    override var dbPollingRate: Long
        get() = stateStore.current().appliedUser.dbPollingRate
        set(value) {
            stateStore.updateUserState(applyImmediately = true) { it.copy(dbPollingRate = value) }
            IrisLogger.debug("DbPollingRate updated to: $dbPollingRate")
        }

    override var messageSendRate: Long
        get() = stateStore.current().appliedUser.messageSendRate
        set(value) {
            stateStore.updateUserState(applyImmediately = true) { it.copy(messageSendRate = value) }
            IrisLogger.debug("MessageSendRate updated to: $messageSendRate")
        }

    override var messageSendJitterMax: Long
        get() = stateStore.current().appliedUser.messageSendJitterMax
        set(value) {
            stateStore.updateUserState(applyImmediately = true) { it.copy(messageSendJitterMax = value) }
            IrisLogger.debug("MessageSendJitterMax updated to: $messageSendJitterMax")
        }

    override fun commandRoutePrefixes(): Map<String, List<String>> =
        stateStore.current().appliedUser.commandRoutePrefixes

    override fun imageMessageTypeRoutes(): Map<String, List<String>> =
        stateStore.current().appliedUser.imageMessageTypeRoutes

    fun configResponse(): ConfigResponse =
        synchronized(this) {
            buildConfigResponse(snapshotConfigValues(), effectiveConfigValues())
        }

    fun configUpdateResponse(
        name: String,
        persisted: Boolean,
        applied: Boolean,
        requiresRestart: Boolean,
    ): ConfigUpdateResponse =
        synchronized(this) {
            buildConfigUpdateResponse(
                status =
                    ConfigUpdateStatus(
                        name = name,
                        persisted = persisted,
                        applied = applied,
                        requiresRestart = requiresRestart,
                    ),
                snapshot = snapshotConfigValues(),
                effective = effectiveConfigValues(),
            )
        }

    private fun snapshotConfigValues(): ConfigValues =
        AppliedConfigState(
            user = stateStore.current().snapshotUser,
            discovered = stateStore.current().discovered,
        ).toLegacyConfigValues()

    private fun effectiveConfigValues(): ConfigValues =
        AppliedConfigState(
            user = stateStore.current().appliedUser,
            discovered = stateStore.current().discovered,
        ).toLegacyConfigValues()
}
