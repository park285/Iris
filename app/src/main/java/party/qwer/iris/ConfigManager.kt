package party.qwer.iris

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import party.qwer.iris.model.ConfigResponse
import party.qwer.iris.model.ConfigUpdateResponse
import party.qwer.iris.model.ConfigValues
import party.qwer.iris.model.UserConfigValues
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class ConfigManager(
    private val configPath: String = System.getenv("IRIS_CONFIG_PATH") ?: "/data/local/tmp/config.json",
) : ConfigProvider {
    private data class ConfigRuntimeState(
        val snapshotUser: UserConfigState = UserConfigState(),
        val appliedUser: UserConfigState = UserConfigState(),
        val discovered: DiscoveredConfigState = DiscoveredConfigState(),
        val isDirty: Boolean = false,
    )

    @Volatile
    private var state = ConfigRuntimeState()

    private val json =
        Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }

    init {
        loadConfig()
    }

    private inline fun mutateState(
        transform: (ConfigRuntimeState) -> ConfigRuntimeState,
    ): ConfigRuntimeState =
        synchronized(this) {
            val updated = transform(state)
            state = updated
            updated
        }

    private inline fun updateUserState(
        applyImmediately: Boolean,
        transform: (UserConfigState) -> UserConfigState,
    ): ConfigRuntimeState =
        mutateState { current ->
            val updatedSnapshot = transform(current.snapshotUser)
            val updatedApplied =
                if (applyImmediately) {
                    transform(current.appliedUser)
                } else {
                    current.appliedUser
                }
            if (updatedSnapshot == current.snapshotUser && updatedApplied == current.appliedUser) {
                current
            } else {
                current.copy(
                    snapshotUser = updatedSnapshot,
                    appliedUser = updatedApplied,
                    isDirty = true,
                )
            }
        }

    private fun loadConfig() {
        val configFile = File(configPath)
        if (!configFile.exists()) {
            IrisLogger.info("config.json not found at $configPath, creating default config.")
            saveConfig()
            return
        }

        try {
            val jsonString = configFile.readText()
            val decodedConfig = decodeConfigValues(json, jsonString)
            val userConfigState = decodedConfig.values.toUserConfigState()
            mutateState {
                ConfigRuntimeState(
                    snapshotUser = userConfigState,
                    appliedUser = userConfigState.copy(),
                    discovered = it.discovered,
                    isDirty = decodedConfig.migratedLegacyEndpoint,
                )
            }
            IrisLogger.debug(
                "Loaded config from $configPath " +
                    "(inboundSigningSecretConfigured=${state.snapshotUser.inboundSigningSecret.isNotBlank()}, " +
                    "outboundWebhookTokenConfigured=${state.snapshotUser.outboundWebhookToken.isNotBlank()}, " +
                    "botControlTokenConfigured=${state.snapshotUser.botControlToken.isNotBlank()})",
            )
            if (decodedConfig.migratedLegacyEndpoint) {
                IrisLogger.info("Migrated legacy webhook config to route-aware model")
            }
        } catch (e: IOException) {
            IrisLogger.error("Error reading config.json from $configPath, using in-memory defaults: ${e.message}")
            backupBrokenConfig(configFile)
        } catch (e: SerializationException) {
            IrisLogger.error("JSON parsing error in config.json from $configPath, using in-memory defaults: ${e.message}")
            backupBrokenConfig(configFile)
        }
    }

    private fun saveConfig(): Boolean {
        val configFile = File(configPath)
        val parentDir = configFile.parentFile
        if (parentDir != null && !parentDir.exists() && !parentDir.mkdirs()) {
            IrisLogger.error("Failed to create config directory: ${parentDir.absolutePath}")
            return false
        }

        val tempFile = File("${configFile.absolutePath}.tmp")
        try {
            val currentState = state
            val jsonString = json.encodeToString(UserConfigValues.serializer(), currentState.snapshotUser.toPersistedConfigValues())
            IrisLogger.debug(
                "Saving config to $configPath " +
                    "(inboundSigningSecretConfigured=${currentState.snapshotUser.inboundSigningSecret.isNotBlank()}, " +
                    "outboundWebhookTokenConfigured=${currentState.snapshotUser.outboundWebhookToken.isNotBlank()}, " +
                    "botControlTokenConfigured=${currentState.snapshotUser.botControlToken.isNotBlank()})",
            )

            FileOutputStream(tempFile).use { output ->
                output.write(jsonString.toByteArray())
                output.fd.sync()
            }
            moveConfigAtomically(tempFile, configFile)
            state = state.copy(isDirty = false)
            return true
        } catch (e: IOException) {
            IrisLogger.error("Error writing config to file $configPath: ${e.message}")
        } catch (e: SerializationException) {
            IrisLogger.error("JSON error while saving config to $configPath: ${e.message}")
        }
        tempFile.delete()
        return false
    }

    fun saveConfigNow(): Boolean {
        synchronized(this) {
            if (!state.isDirty) {
                return true
            }
            return saveConfig()
        }
    }

    override var botId: Long
        get() = state.discovered.botId
        set(value) {
            mutateState { current ->
                if (current.discovered.botId == value) {
                    current
                } else {
                    current.copy(discovered = current.discovered.copy(botId = value))
                }
            }
            IrisLogger.debug("Bot Id is updated to: $botId")
        }

    override var botName: String
        get() = state.appliedUser.botName
        set(value) {
            updateUserState(applyImmediately = true) { it.copy(botName = value) }
            IrisLogger.debug("Bot name updated to: $botName")
        }

    override var botSocketPort: Int
        get() = state.appliedUser.botHttpPort
        set(value) {
            updateUserState(applyImmediately = false) { it.copy(botHttpPort = value) }
            IrisLogger.debug(
                "Bot port snapshot updated to: ${state.snapshotUser.botHttpPort} " +
                    "(effective=${state.appliedUser.botHttpPort})",
            )
        }

    var defaultWebhookEndpoint: String
        get() = state.appliedUser.endpoint
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

            val updatedSnapshot =
                updateWebhookConfig(
                    state.snapshotUser.toLegacyConfigValues(),
                    normalizedRoute,
                    normalizedEndpoint,
                ).toUserConfigState()
            val updatedEffective =
                updateWebhookConfig(
                    state.appliedUser.toLegacyConfigValues(),
                    normalizedRoute,
                    normalizedEndpoint,
                ).toUserConfigState()
            if (state.snapshotUser == updatedSnapshot && state.appliedUser == updatedEffective) {
                return
            }

            state =
                state.copy(
                    snapshotUser = updatedSnapshot,
                    appliedUser = updatedEffective,
                    isDirty = true,
                )
            if (normalizedRoute == DEFAULT_WEBHOOK_ROUTE) {
                IrisLogger.debug("Default webhook endpoint updated")
            } else {
                IrisLogger.debug("Webhook endpoint updated for route=$normalizedRoute")
            }
        }
    }

    override fun webhookEndpointFor(route: String): String = configuredWebhookEndpoint(state.appliedUser.toLegacyConfigValues(), route)

    override val inboundSigningSecret: String
        get() = state.snapshotUser.inboundSigningSecret

    override val outboundWebhookToken: String
        get() = state.snapshotUser.outboundWebhookToken

    override val botControlToken: String
        get() = state.snapshotUser.botControlToken

    internal fun signingSecret(): String = state.snapshotUser.inboundSigningSecret

    override var dbPollingRate: Long
        get() = state.appliedUser.dbPollingRate
        set(value) {
            updateUserState(applyImmediately = true) { it.copy(dbPollingRate = value) }
            IrisLogger.debug("DbPollingRate updated to: $dbPollingRate")
        }

    override var messageSendRate: Long
        get() = state.appliedUser.messageSendRate
        set(value) {
            updateUserState(applyImmediately = true) { it.copy(messageSendRate = value) }
            IrisLogger.debug("MessageSendRate updated to: $messageSendRate")
        }

    override var messageSendJitterMax: Long
        get() = state.appliedUser.messageSendJitterMax
        set(value) {
            updateUserState(applyImmediately = true) { it.copy(messageSendJitterMax = value) }
            IrisLogger.debug("MessageSendJitterMax updated to: $messageSendJitterMax")
        }

    override fun commandRoutePrefixes(): Map<String, List<String>> = state.appliedUser.commandRoutePrefixes

    override fun imageMessageTypeRoutes(): Map<String, List<String>> = state.appliedUser.imageMessageTypeRoutes

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

    private fun snapshotConfigValues(): ConfigValues = AppliedConfigState(user = state.snapshotUser, discovered = state.discovered).toLegacyConfigValues()

    private fun effectiveConfigValues(): ConfigValues = AppliedConfigState(user = state.appliedUser, discovered = state.discovered).toLegacyConfigValues()

    private fun backupBrokenConfig(configFile: File) {
        if (!configFile.exists()) {
            return
        }

        val backupFile = File("${configFile.absolutePath}.bak")
        runCatching {
            Files.move(
                configFile.toPath(),
                backupFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
            IrisLogger.info("Backed up unreadable config to ${backupFile.absolutePath}")
        }.onFailure { error ->
            IrisLogger.error("Failed to back up unreadable config: ${error.message}")
        }
    }

    @Throws(IOException::class)
    private fun moveConfigAtomically(
        tempFile: File,
        targetFile: File,
    ) {
        try {
            Files.move(
                tempFile.toPath(),
                targetFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                tempFile.toPath(),
                targetFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }
}
