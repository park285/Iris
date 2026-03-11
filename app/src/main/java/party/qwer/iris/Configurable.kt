package party.qwer.iris

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import party.qwer.iris.model.ConfigValues
import java.io.File
import java.io.IOException

class Configurable {
    companion object {
        private const val DEFAULT_WEBHOOK_ROUTE = "hololive"
        private val CONFIG_FILE_PATH: String by lazy {
            System.getenv("IRIS_CONFIG_PATH") ?: "/data/local/tmp/config.json"
        }

        @Volatile
        private var configValues: ConfigValues = ConfigValues()

        @Volatile
        private var isDirty = false

        private val json =
            Json {
                encodeDefaults = true
                ignoreUnknownKeys = true
            }

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
                configValues = json.decodeFromString(ConfigValues.serializer(), jsonString)
                IrisLogger.debug(
                    "Loaded config from $CONFIG_FILE_PATH " +
                        "(webhookTokenConfigured=${configValues.webhookToken.isNotBlank()}, " +
                        "botTokenConfigured=${configValues.botToken.isNotBlank()})",
                )
            } catch (e: IOException) {
                IrisLogger.error("Error reading config.json from $CONFIG_FILE_PATH, creating default config: ${e.message}")
                saveConfig()
            } catch (e: SerializationException) {
                IrisLogger.error("JSON parsing error in config.json from $CONFIG_FILE_PATH, creating default config: ${e.message}")
                saveConfig()
            }
        }

        private fun saveConfig() {
            try {
                val jsonString = json.encodeToString(ConfigValues.serializer(), configValues)
                IrisLogger.debug(
                    "Saving config to $CONFIG_FILE_PATH " +
                        "(webhookTokenConfigured=${configValues.webhookToken.isNotBlank()}, " +
                        "botTokenConfigured=${configValues.botToken.isNotBlank()})",
                )

                File(CONFIG_FILE_PATH).writeText(jsonString)
                isDirty = false
            } catch (e: IOException) {
                IrisLogger.error("Error writing config to file $CONFIG_FILE_PATH: ${e.message}")
            } catch (e: SerializationException) {
                IrisLogger.error("JSON error while saving config to $CONFIG_FILE_PATH: ${e.message}")
            }
        }

        fun saveConfigNow() {
            if (isDirty) {
                saveConfig()
            }
        }

        private fun markDirty() {
            isDirty = true
        }

        var botId: Long
            get() = configValues.botId
            set(value) {
                if (configValues.botId == value) {
                    return
                }
                configValues.botId = value
                markDirty()
                IrisLogger.debug("Bot Id is updated to: $botId")
            }

        var botName: String
            get() = configValues.botName
            set(value) {
                if (configValues.botName == value) {
                    return
                }
                configValues.botName = value
                markDirty()
                IrisLogger.debug("Bot name updated to: $botName")
            }

        var botSocketPort: Int
            get() = configValues.botHttpPort
            set(value) {
                if (configValues.botHttpPort == value) {
                    return
                }
                configValues.botHttpPort = value
                markDirty()
                IrisLogger.debug("Bot port updated to: $botSocketPort")
            }

        var defaultWebhookEndpoint: String
            get() = configValues.webhooks[DEFAULT_WEBHOOK_ROUTE].orEmpty()
            set(value) {
                val normalized = value.trim()
                if (defaultWebhookEndpoint == normalized) {
                    return
                }
                configValues.webhooks =
                    if (normalized.isBlank()) {
                        emptyMap()
                    } else {
                        mapOf(DEFAULT_WEBHOOK_ROUTE to normalized)
                    }
                markDirty()
                IrisLogger.debug("Default webhook endpoint updated for route '$DEFAULT_WEBHOOK_ROUTE'")
            }

        val webhookToken: String
            get() = configValues.webhookToken.ifBlank { System.getenv("IRIS_WEBHOOK_TOKEN") ?: "" }

        val botToken: String
            get() = configValues.botToken.ifBlank { System.getenv("IRIS_BOT_TOKEN") ?: "" }

        var dbPollingRate: Long
            get() = configValues.dbPollingRate
            set(value) {
                if (configValues.dbPollingRate == value) {
                    return
                }
                configValues.dbPollingRate = value
                markDirty()
                IrisLogger.debug("DbPollingRate updated to: $dbPollingRate")
            }

        var messageSendRate: Long
            get() = configValues.messageSendRate
            set(value) {
                if (configValues.messageSendRate == value) {
                    return
                }
                configValues.messageSendRate = value
                markDirty()
                IrisLogger.debug("MessageSendRate updated to: $messageSendRate")
                Replier.restartMessageSender()
            }
    }
}
