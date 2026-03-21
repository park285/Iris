package party.qwer.iris

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import party.qwer.iris.model.ConfigResponse
import party.qwer.iris.model.ConfigUpdateResponse
import party.qwer.iris.model.ConfigValues
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class Configurable {
    companion object {
        private val CONFIG_FILE_PATH: String by lazy {
            System.getenv("IRIS_CONFIG_PATH") ?: "/data/local/tmp/config.json"
        }

        @Volatile
        private var snapshotValues: ConfigValues = ConfigValues()

        @Volatile
        private var effectiveValues: ConfigValues = ConfigValues()

        @Volatile
        private var isDirty = false

        private val json =
            Json {
                encodeDefaults = true
                ignoreUnknownKeys = true
            }

        var onMessageSendRateChanged: (() -> Unit)? = null

        init {
            loadConfig()
            Runtime.getRuntime().addShutdownHook(
                Thread {
                    if (isDirty) {
                        saveConfig()
                    }
                },
            )
        }

        private fun loadConfig() {
            val configFile = File(CONFIG_FILE_PATH)
            if (!configFile.exists()) {
                IrisLogger.info("config.json not found at $CONFIG_FILE_PATH, creating default config.")
                saveConfig()
                return
            }

            try {
                val jsonString = configFile.readText()
                val decodedConfig = decodeConfigValues(json, jsonString)
                snapshotValues = decodedConfig.values
                effectiveValues = decodedConfig.values.copy()
                IrisLogger.debug(
                    "Loaded config from $CONFIG_FILE_PATH " +
                        "(webhookTokenConfigured=${snapshotValues.webhookToken.isNotBlank()}, " +
                        "botTokenConfigured=${snapshotValues.botToken.isNotBlank()})",
                )
                if (decodedConfig.migratedLegacyEndpoint) {
                    IrisLogger.info("Migrated legacy webhook config to route-aware model")
                }
                isDirty = decodedConfig.migratedLegacyEndpoint
            } catch (e: IOException) {
                IrisLogger.error("Error reading config.json from $CONFIG_FILE_PATH, using in-memory defaults: ${e.message}")
                backupBrokenConfig(configFile)
            } catch (e: SerializationException) {
                IrisLogger.error("JSON parsing error in config.json from $CONFIG_FILE_PATH, using in-memory defaults: ${e.message}")
                backupBrokenConfig(configFile)
            }
        }

        private fun saveConfig(): Boolean {
            val configFile = File(CONFIG_FILE_PATH)
            val parentDir = configFile.parentFile
            if (parentDir != null && !parentDir.exists() && !parentDir.mkdirs()) {
                IrisLogger.error("Failed to create config directory: ${parentDir.absolutePath}")
                return false
            }

            val tempFile = File("${configFile.absolutePath}.tmp")
            try {
                val jsonString = json.encodeToString(ConfigValues.serializer(), snapshotValues)
                IrisLogger.debug(
                    "Saving config to $CONFIG_FILE_PATH " +
                        "(webhookTokenConfigured=${snapshotValues.webhookToken.isNotBlank()}, " +
                        "botTokenConfigured=${snapshotValues.botToken.isNotBlank()})",
                )

                tempFile.writeText(jsonString)
                moveConfigAtomically(tempFile, configFile)
                isDirty = false
                return true
            } catch (e: IOException) {
                IrisLogger.error("Error writing config to file $CONFIG_FILE_PATH: ${e.message}")
            } catch (e: SerializationException) {
                IrisLogger.error("JSON error while saving config to $CONFIG_FILE_PATH: ${e.message}")
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

        var botId: Long
            get() = effectiveValues.botId
            set(value) {
                synchronized(this) {
                    if (snapshotValues.botId == value && effectiveValues.botId == value) {
                        return
                    }
                    snapshotValues = snapshotValues.copy(botId = value)
                    effectiveValues = effectiveValues.copy(botId = value)
                    markDirty()
                    IrisLogger.debug("Bot Id is updated to: $botId")
                }
            }

        var botName: String
            get() = effectiveValues.botName
            set(value) {
                synchronized(this) {
                    if (snapshotValues.botName == value && effectiveValues.botName == value) {
                        return
                    }
                    snapshotValues = snapshotValues.copy(botName = value)
                    effectiveValues = effectiveValues.copy(botName = value)
                    markDirty()
                    IrisLogger.debug("Bot name updated to: $botName")
                }
            }

        var botSocketPort: Int
            get() = effectiveValues.botHttpPort
            set(value) {
                synchronized(this) {
                    if (snapshotValues.botHttpPort == value) {
                        return
                    }
                    snapshotValues = snapshotValues.copy(botHttpPort = value)
                    markDirty()
                    IrisLogger.debug(
                        "Bot port snapshot updated to: ${snapshotValues.botHttpPort} " +
                            "(effective=${effectiveValues.botHttpPort})",
                    )
                }
            }

        var defaultWebhookEndpoint: String
            get() = effectiveValues.endpoint
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

                val updatedSnapshot = updateWebhookConfig(snapshotValues, normalizedRoute, normalizedEndpoint)
                val updatedEffective = updateWebhookConfig(effectiveValues, normalizedRoute, normalizedEndpoint)
                if (snapshotValues == updatedSnapshot && effectiveValues == updatedEffective) {
                    return
                }

                snapshotValues = updatedSnapshot
                effectiveValues = updatedEffective
                markDirty()
                if (normalizedRoute == DEFAULT_WEBHOOK_ROUTE) {
                    IrisLogger.debug("Default webhook endpoint updated")
                } else {
                    IrisLogger.debug("Webhook endpoint updated for route=$normalizedRoute")
                }
            }
        }

        fun webhookEndpointFor(route: String): String = configuredWebhookEndpoint(effectiveValues, route)

        val webhookToken: String
            get() = snapshotValues.webhookToken.ifBlank { System.getenv("IRIS_WEBHOOK_TOKEN") ?: "" }

        val botToken: String
            get() = snapshotValues.botToken.ifBlank { System.getenv("IRIS_BOT_TOKEN") ?: "" }

        var dbPollingRate: Long
            get() = effectiveValues.dbPollingRate
            set(value) {
                synchronized(this) {
                    if (snapshotValues.dbPollingRate == value && effectiveValues.dbPollingRate == value) {
                        return
                    }
                    snapshotValues = snapshotValues.copy(dbPollingRate = value)
                    effectiveValues = effectiveValues.copy(dbPollingRate = value)
                    markDirty()
                    IrisLogger.debug("DbPollingRate updated to: $dbPollingRate")
                }
            }

        var messageSendRate: Long
            get() = effectiveValues.messageSendRate
            set(value) {
                val callback =
                    synchronized(this) {
                        if (snapshotValues.messageSendRate == value && effectiveValues.messageSendRate == value) {
                            return
                        }
                        snapshotValues = snapshotValues.copy(messageSendRate = value)
                        effectiveValues = effectiveValues.copy(messageSendRate = value)
                        markDirty()
                        IrisLogger.debug("MessageSendRate updated to: $messageSendRate")
                        onMessageSendRateChanged
                    }
                callback?.invoke()
            }

        fun configResponse(): ConfigResponse =
            synchronized(this) {
                buildConfigResponse(snapshotValues.copy(), effectiveValues.copy())
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
                    snapshot = snapshotValues.copy(),
                    effective = effectiveValues.copy(),
                )
            }

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
}
