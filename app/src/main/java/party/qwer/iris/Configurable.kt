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
                IrisLogger.debug("jsonString from file: $jsonString")
                configValues = json.decodeFromString(ConfigValues.serializer(), jsonString)
                saveConfig()
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
                IrisLogger.debug("saveConfig: configValues before serialization: $configValues")

                val jsonString = json.encodeToString(ConfigValues.serializer(), configValues)
                IrisLogger.debug("saveConfig: jsonString: $jsonString")

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
                configValues.botId = value
                markDirty()
                IrisLogger.debug("Bot Id is updated to: $botId")
            }

        var botName: String
            get() = configValues.botName
            set(value) {
                configValues.botName = value
                markDirty()
                IrisLogger.debug("Bot name updated to: $botName")
            }

        var botSocketPort: Int
            get() = configValues.botHttpPort
            set(value) {
                configValues.botHttpPort = value
                markDirty()
                IrisLogger.debug("Bot port updated to: $botSocketPort")
            }

        val webhooks: Map<String, String>
            get() {
                val configured = configValues.webhooks.filterValues { it.isNotBlank() }
                if (configured.isNotEmpty()) {
                    return configured
                }

                val envFallback = mutableMapOf<String, String>()
                System.getenv("IRIS_WEBHOOK_HOLOLIVE")?.takeIf { it.isNotBlank() }?.let { envFallback["hololive"] = it }
                System.getenv("IRIS_WEBHOOK_TWENTYQ")?.takeIf { it.isNotBlank() }?.let { envFallback["twentyq"] = it }
                System.getenv("IRIS_WEBHOOK_TURTLE_SOUP")?.takeIf { it.isNotBlank() }?.let { envFallback["turtle-soup"] = it }
                return envFallback
            }

        var defaultWebhookEndpoint: String
            get() = webhooks[DEFAULT_WEBHOOK_ROUTE].orEmpty()
            set(value) {
                val normalized = value.trim()
                val updatedWebhooks =
                    configValues.webhooks.toMutableMap().apply {
                        if (normalized.isBlank()) {
                            remove(DEFAULT_WEBHOOK_ROUTE)
                        } else {
                            put(DEFAULT_WEBHOOK_ROUTE, normalized)
                        }
                    }
                configValues.webhooks = updatedWebhooks.toMap()
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
                configValues.dbPollingRate = value
                markDirty()
                IrisLogger.debug("DbPollingRate updated to: $dbPollingRate")
            }

        var messageSendRate: Long
            get() = configValues.messageSendRate
            set(value) {
                configValues.messageSendRate = value
                markDirty()
                IrisLogger.debug("MessageSendRate updated to: $messageSendRate")
                Replier.restartMessageSender()
            }

        var messageMaxChars: Int
            get() = configValues.messageMaxChars
            set(value) {
                configValues.messageMaxChars = value
                markDirty()
                IrisLogger.debug("MessageMaxChars updated to: $messageMaxChars")
            }

        var relayUserId: String
            get() = configValues.relayUserId
            set(value) {
                configValues.relayUserId = value
                markDirty()
            }

        var relayUserEmail: String
            get() = configValues.relayUserEmail
            set(value) {
                configValues.relayUserEmail = value
                markDirty()
            }

        var relaySessionId: String
            get() = configValues.relaySessionId
            set(value) {
                configValues.relaySessionId = value
                markDirty()
            }

        var relaySecret: String
            get() = configValues.relaySecret
            set(value) {
                configValues.relaySecret = value
                markDirty()
            }
    }
}
