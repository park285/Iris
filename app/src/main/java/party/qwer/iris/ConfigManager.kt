package party.qwer.iris

import kotlinx.serialization.json.Json
import party.qwer.iris.config.ConfigLoadResult
import party.qwer.iris.config.ConfigPathPolicy
import party.qwer.iris.config.ConfigPersistence
import party.qwer.iris.config.ConfigStateStore
import party.qwer.iris.http.RuntimeBootstrapState
import party.qwer.iris.http.RuntimeConfigReadiness
import party.qwer.iris.model.ConfigResponse
import party.qwer.iris.model.ConfigValues

class ConfigManager(
    private val configPath: String = ConfigPathPolicy.resolveConfigPath(),
    private val env: Map<String, String> = System.getenv(),
) : ConfigProvider {
    private val json =
        Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }

    private val stateStore = ConfigStateStore()
    private val persistence = ConfigPersistence(configPath, json)
    private val bridgeRequirementOverride = parseBridgeRequirementOverride(env["IRIS_REQUIRE_BRIDGE"])
    private val readyVerboseOverride = parseReadyVerboseOverride(env["IRIS_READY_VERBOSE"])

    init {
        loadConfig()
    }

    private fun loadConfig() {
        when (val loaded = persistence.load()) {
            ConfigLoadResult.Missing -> {
                IrisLogger.info("config.json not found at $configPath, creating default config.")
                stateStore.mutate { it.copy(isDirty = true) }
                saveConfig()
                return
            }

            is ConfigLoadResult.Invalid -> {
                throw IllegalStateException(
                    "Existing config at $configPath is invalid and requires operator recovery: ${loaded.reason}",
                )
            }

            is ConfigLoadResult.Loaded -> {
                stateStore.mutate {
                    ConfigRuntimeState(
                        snapshotUser = loaded.config.userState,
                        appliedUser = loaded.config.userState.copy(),
                        discovered = it.discovered,
                        isDirty = loaded.config.migratedLegacyConfig,
                    )
                }
                IrisLogger.debug(
                    "Loaded config from $configPath " +
                        "(inboundSigningSecretConfigured=${stateStore.current().snapshotUser.inboundSigningSecret.isNotBlank()}, " +
                        "outboundWebhookTokenConfigured=${stateStore.current().snapshotUser.outboundWebhookToken.isNotBlank()}, " +
                        "botControlTokenConfigured=${stateStore.current().snapshotUser.botControlToken.isNotBlank()}, " +
                        "bridgeTokenConfigured=${stateStore.current().snapshotUser.bridgeToken.isNotBlank()})",
                )
                if (loaded.config.migratedLegacyEndpoint) {
                    IrisLogger.info("Migrated legacy webhook config to route-aware model")
                }
                if (loaded.config.migratedLegacySecrets) {
                    IrisLogger.info("Migrated legacy secret config to role-aware fields")
                }
            }
        }
    }

    private fun saveConfig(): Boolean {
        val savedSnapshot = stateStore.current().snapshotUser
        IrisLogger.debug(
            "Saving config to $configPath " +
                "(inboundSigningSecretConfigured=${savedSnapshot.inboundSigningSecret.isNotBlank()}, " +
                "outboundWebhookTokenConfigured=${savedSnapshot.outboundWebhookToken.isNotBlank()}, " +
                "botControlTokenConfigured=${savedSnapshot.botControlToken.isNotBlank()}, " +
                "bridgeTokenConfigured=${savedSnapshot.bridgeToken.isNotBlank()})",
        )
        val success = persistence.save(savedSnapshot)
        if (success) {
            stateStore.clearDirtyIf(savedSnapshot)
        }
        return success
    }

    @Synchronized
    fun saveConfigNow(): Boolean {
        if (!stateStore.current().isDirty) {
            return true
        }
        return saveConfig()
    }

    @Synchronized
    internal fun applyConfigMutation(planBuilder: (UserConfigState) -> PlannedConfigUpdate): ConfigUpdateOutcome {
        val current = stateStore.current()
        val plannedUpdate = planBuilder(current.snapshotUser)
        val candidateRuntime = candidateRuntime(current, plannedUpdate.plan)
        val committedState =
            if (
                !current.isDirty &&
                candidateRuntime.snapshotUser == current.snapshotUser &&
                candidateRuntime.appliedUser == current.appliedUser
            ) {
                current
            } else {
                persistThenCommit(candidateRuntime)
            }
        val persisted = committedState != null
        return ConfigUpdateOutcome(
            name = plannedUpdate.name,
            persisted = persisted,
            applied = plannedUpdate.applied,
            requiresRestart = plannedUpdate.requiresRestart,
            response =
                committedState?.let { committed ->
                    buildConfigUpdateResponse(
                        status =
                            ConfigUpdateStatus(
                                name = plannedUpdate.name,
                                persisted = true,
                                applied = plannedUpdate.applied,
                                requiresRestart = plannedUpdate.requiresRestart,
                            ),
                        snapshot = snapshotConfigValues(committed),
                        effective = effectiveConfigValues(committed),
                    )
                },
        )
    }

    internal fun persistThenCommit(candidateRuntime: ConfigRuntimeState): ConfigRuntimeState? {
        if (!persistence.save(candidateRuntime.snapshotUser)) {
            return null
        }

        val committedRuntime = candidateRuntime.copy(isDirty = false)
        stateStore.replace(committedRuntime)
        return committedRuntime
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
        val normalizedRoute = route.trim()
        val normalizedEndpoint = endpoint.trim()
        if (normalizedRoute.isEmpty()) {
            return
        }

        stateStore.mutate { current ->
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
                current
            } else {
                current.copy(
                    snapshotUser = updatedSnapshot,
                    appliedUser = updatedEffective,
                    isDirty = true,
                )
            }
        }
        if (normalizedRoute == DEFAULT_WEBHOOK_ROUTE) {
            IrisLogger.debug("Default webhook endpoint updated")
        } else {
            IrisLogger.debug("Webhook endpoint updated for route=$normalizedRoute")
        }
    }

    override fun webhookEndpointFor(route: String): String = configuredWebhookEndpoint(stateStore.current().appliedUser.toLegacyConfigValues(), route)

    override val inboundSigningSecret: String
        get() = stateStore.current().appliedUser.inboundSigningSecret

    override val outboundWebhookToken: String
        get() = stateStore.current().appliedUser.outboundWebhookToken

    override val botControlToken: String
        get() = stateStore.current().appliedUser.botControlToken

    override fun activeInboundSigningSecret(): String = stateStore.current().appliedUser.inboundSigningSecret

    override fun activeOutboundWebhookToken(): String = stateStore.current().appliedUser.outboundWebhookToken

    override fun activeBotControlToken(): String = stateStore.current().appliedUser.botControlToken

    internal fun signingSecret(): String = activeInboundSigningSecret()

    internal fun snapshotUserState(): UserConfigState = stateStore.current().snapshotUser

    internal fun runtimeConfigReadiness(): RuntimeConfigReadiness {
        val appliedUser = stateStore.current().appliedUser
        val envBridgeTokenConfigured = env["IRIS_BRIDGE_TOKEN"]?.trim()?.isNotEmpty() == true
        val bridgeTokenConfigured = appliedUser.bridgeToken.isNotBlank() || envBridgeTokenConfigured
        return RuntimeConfigReadiness(
            inboundSigningSecretConfigured = appliedUser.inboundSigningSecret.isNotBlank(),
            outboundWebhookTokenConfigured = appliedUser.outboundWebhookToken.isNotBlank(),
            botControlTokenConfigured = appliedUser.botControlToken.isNotBlank(),
            bridgeTokenConfigured = bridgeTokenConfigured,
            defaultWebhookEndpointConfigured =
                configuredWebhookEndpoint(
                    appliedUser.toLegacyConfigValues(),
                    DEFAULT_WEBHOOK_ROUTE,
                ).isNotBlank(),
            bridgeRequired =
                resolveBridgeRequirement(
                    bridgeRequirementOverride = bridgeRequirementOverride,
                    bridgeTokenConfigured = bridgeTokenConfigured,
                ),
        )
    }

    internal fun runtimeBootstrapState(): RuntimeBootstrapState = runtimeConfigReadiness().bootstrapState()

    internal fun readyVerbose(): Boolean = readyVerboseOverride

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

    override fun commandRoutePrefixes(): Map<String, List<String>> = stateStore.current().appliedUser.commandRoutePrefixes

    override fun imageMessageTypeRoutes(): Map<String, List<String>> = stateStore.current().appliedUser.imageMessageTypeRoutes

    fun configResponse(): ConfigResponse {
        val current = stateStore.current()
        return buildConfigResponse(
            snapshotConfigValues(current),
            effectiveConfigValues(current),
        )
    }

    private fun snapshotConfigValues(state: ConfigRuntimeState = stateStore.current()): ConfigValues =
        AppliedConfigState(
            user = state.snapshotUser,
            discovered = state.discovered,
        ).toLegacyConfigValues()

    private fun effectiveConfigValues(state: ConfigRuntimeState = stateStore.current()): ConfigValues =
        AppliedConfigState(
            user = state.appliedUser,
            discovered = state.discovered,
        ).toLegacyConfigValues()

    private fun candidateRuntime(
        current: ConfigRuntimeState,
        plan: ConfigMutationPlan,
    ): ConfigRuntimeState =
        current.copy(
            snapshotUser = plan.candidateSnapshot,
            appliedUser =
                if (plan.applyImmediately) {
                    plan.candidateSnapshot
                } else {
                    current.appliedUser
                },
            isDirty = true,
        )
}

private fun resolveBridgeRequirement(
    bridgeRequirementOverride: Boolean?,
    bridgeTokenConfigured: Boolean,
): Boolean = bridgeRequirementOverride ?: bridgeTokenConfigured

private fun parseBridgeRequirementOverride(rawValue: String?): Boolean? {
    val normalized = rawValue?.trim()?.lowercase() ?: return null
    return when (normalized) {
        "1",
        "true",
        "on",
        -> true

        "0",
        "false",
        "off",
        -> false

        else -> {
            IrisLogger.warn(
                "[ConfigManager] IRIS_REQUIRE_BRIDGE has invalid value '$rawValue'; " +
                    "falling back to automatic bridge readiness detection",
            )
            null
        }
    }
}

private fun parseReadyVerboseOverride(rawValue: String?): Boolean {
    val normalized = rawValue?.trim()?.lowercase() ?: return false
    return when (normalized) {
        "1",
        "true",
        "on",
        -> true

        "0",
        "false",
        "off",
        -> false

        else -> {
            IrisLogger.warn(
                "[ConfigManager] IRIS_READY_VERBOSE has invalid value '$rawValue'; " +
                    "defaulting to false",
            )
            false
        }
    }
}
