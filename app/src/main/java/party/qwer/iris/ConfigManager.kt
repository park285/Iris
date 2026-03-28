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
    @Volatile
    private var snapshotUser: UserConfigState = UserConfigState()

    @Volatile
    private var appliedUser: UserConfigState = UserConfigState()

    @Volatile
    private var discoveredState: DiscoveredConfigState = DiscoveredConfigState()

    @Volatile
    private var isDirty = false

    private val json =
        Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }

    init {
        loadConfig()
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
            snapshotUser = decodedConfig.values.toUserConfigState()
            appliedUser = snapshotUser.copy()
            discoveredState = decodedConfig.values.toDiscoveredConfigState()
            IrisLogger.debug(
                "Loaded config from $configPath " +
                    "(webhookTokenConfigured=${snapshotUser.webhookToken.isNotBlank()}, " +
                    "botTokenConfigured=${snapshotUser.botToken.isNotBlank()})",
            )
            if (decodedConfig.migratedLegacyEndpoint) {
                IrisLogger.info("Migrated legacy webhook config to route-aware model")
            }
            isDirty = decodedConfig.migratedLegacyEndpoint
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
            val jsonString = json.encodeToString(UserConfigValues.serializer(), snapshotUser.toPersistedConfigValues())
            IrisLogger.debug(
                "Saving config to $configPath " +
                    "(webhookTokenConfigured=${snapshotUser.webhookToken.isNotBlank()}, " +
                    "botTokenConfigured=${snapshotUser.botToken.isNotBlank()})",
            )

            FileOutputStream(tempFile).use { output ->
                output.write(jsonString.toByteArray())
                output.fd.sync()
            }
            moveConfigAtomically(tempFile, configFile)
            isDirty = false
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
            if (!isDirty) {
                return true
            }
            return saveConfig()
        }
    }

    private fun markDirty() {
        isDirty = true
    }

    override var botId: Long
        get() = discoveredState.botId
        set(value) {
            synchronized(this) {
                if (discoveredState.botId == value) {
                    return
                }
                discoveredState = discoveredState.copy(botId = value)
                IrisLogger.debug("Bot Id is updated to: $botId")
            }
        }

    override var botName: String
        get() = appliedUser.botName
        set(value) {
            synchronized(this) {
                if (snapshotUser.botName == value && appliedUser.botName == value) {
                    return
                }
                snapshotUser = snapshotUser.copy(botName = value)
                appliedUser = appliedUser.copy(botName = value)
                markDirty()
                IrisLogger.debug("Bot name updated to: $botName")
            }
        }

    override var botSocketPort: Int
        get() = appliedUser.botHttpPort
        set(value) {
            synchronized(this) {
                if (snapshotUser.botHttpPort == value) {
                    return
                }
                snapshotUser = snapshotUser.copy(botHttpPort = value)
                markDirty()
                IrisLogger.debug(
                    "Bot port snapshot updated to: ${snapshotUser.botHttpPort} " +
                        "(effective=${appliedUser.botHttpPort})",
                )
            }
        }

    var defaultWebhookEndpoint: String
        get() = appliedUser.endpoint
        set(value) {
            val normalized = value.trim()
            synchronized(this) {
                if (defaultWebhookEndpoint == normalized) {
                    return
                }
                setWebhookEndpoint(DEFAULT_WEBHOOK_ROUTE, normalized)
            }
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

            val updatedSnapshot = updateWebhookConfig(snapshotUser.toLegacyConfigValues(), normalizedRoute, normalizedEndpoint).toUserConfigState()
            val updatedEffective = updateWebhookConfig(appliedUser.toLegacyConfigValues(), normalizedRoute, normalizedEndpoint).toUserConfigState()
            if (snapshotUser == updatedSnapshot && appliedUser == updatedEffective) {
                return
            }

            snapshotUser = updatedSnapshot
            appliedUser = updatedEffective
            markDirty()
            if (normalizedRoute == DEFAULT_WEBHOOK_ROUTE) {
                IrisLogger.debug("Default webhook endpoint updated")
            } else {
                IrisLogger.debug("Webhook endpoint updated for route=$normalizedRoute")
            }
        }
    }

    override fun webhookEndpointFor(route: String): String = configuredWebhookEndpoint(appliedUser.toLegacyConfigValues(), route)

    override val webhookToken: String
        get() = snapshotUser.webhookToken

    override val botToken: String
        get() = snapshotUser.botToken

    internal fun signingSecret(): String = snapshotUser.webhookToken

    override var dbPollingRate: Long
        get() = appliedUser.dbPollingRate
        set(value) {
            synchronized(this) {
                if (snapshotUser.dbPollingRate == value && appliedUser.dbPollingRate == value) {
                    return
                }
                snapshotUser = snapshotUser.copy(dbPollingRate = value)
                appliedUser = appliedUser.copy(dbPollingRate = value)
                markDirty()
                IrisLogger.debug("DbPollingRate updated to: $dbPollingRate")
            }
        }

    override var messageSendRate: Long
        get() = appliedUser.messageSendRate
        set(value) {
            synchronized(this) {
                if (snapshotUser.messageSendRate == value && appliedUser.messageSendRate == value) {
                    return
                }
                snapshotUser = snapshotUser.copy(messageSendRate = value)
                appliedUser = appliedUser.copy(messageSendRate = value)
                markDirty()
                IrisLogger.debug("MessageSendRate updated to: $messageSendRate")
            }
        }

    override fun commandRoutePrefixes(): Map<String, List<String>> = appliedUser.commandRoutePrefixes

    override fun imageMessageTypeRoutes(): Map<String, List<String>> = appliedUser.imageMessageTypeRoutes

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
            user = snapshotUser,
            discovered = discoveredState,
        ).toLegacyConfigValues()

    private fun effectiveConfigValues(): ConfigValues =
        AppliedConfigState(
            user = appliedUser,
            discovered = discoveredState,
        ).toLegacyConfigValues()

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
