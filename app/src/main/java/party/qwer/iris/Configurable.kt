package party.qwer.iris

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.util.UUID
import party.qwer.iris.model.ConfigValues


class Configurable {
    companion object {
        private val CONFIG_FILE_PATH: String by lazy {
            System.getenv("IRIS_CONFIG_PATH") ?: "/data/local/tmp/config.json"
        }
        private var configValues: ConfigValues = ConfigValues()
        private val runtimeSessionId: String = UUID.randomUUID().toString()

        private val json = Json {
            encodeDefaults = true
        }

        init {
            loadConfig()
        }

        private fun loadConfig() {
            val configFile = File(CONFIG_FILE_PATH)
            if (!configFile.exists()) {
                println("config.json not found at $CONFIG_FILE_PATH, creating default config.")
                saveConfig()
                return
            }

            try {
                val jsonString = configFile.readText()
                println("jsonString from file: $jsonString")
                configValues = json.decodeFromString(ConfigValues.serializer(), jsonString)
            } catch (e: IOException) {
                println("Error reading config.json from $CONFIG_FILE_PATH, creating default config: ${e.message}")
                saveConfig()
            } catch (e: SerializationException) {
                System.err.println("JSON parsing error in config.json from $CONFIG_FILE_PATH, creating default config: ${e.message}")
                saveConfig()
            }
        }

        private fun saveConfig() {
            try {
                println("saveConfig: configValues before serialization: $configValues")

                val jsonString = json.encodeToString(ConfigValues.serializer(), configValues)
                println("saveConfig: jsonString: $jsonString")

                File(CONFIG_FILE_PATH).writeText(jsonString)
            } catch (e: IOException) {
                System.err.println("Error writing config to file $CONFIG_FILE_PATH: ${e.message}")
            } catch (e: SerializationException) {
                System.err.println("JSON error while saving config to $CONFIG_FILE_PATH: ${e.message}")
            }
        }

        var botId: Long
            get() = configValues.botId
            set(value) {
                configValues.botId = value
                saveConfig()
                println("Bot Id is updated to: $botId")
            }

        var botName: String
            get() = configValues.botName
            set(value) {
                configValues.botName = value
                saveConfig()
                println("Bot name updated to: $botName")
            }

        var botSocketPort: Int
            get() = configValues.botHttpPort
            set(value) {
                configValues.botHttpPort = value
                saveConfig()
                println("Bot port updated to: $botSocketPort")
            }

        var dbPollingRate: Long
            get() = configValues.dbPollingRate
            set(value) {
                configValues.dbPollingRate = value
                saveConfig()
                println("DbPollingRate updated to: $dbPollingRate")
            }

        var messageSendRate: Long
            get() = configValues.messageSendRate
            set(value) {
                configValues.messageSendRate = value
                saveConfig()
                println("MessageSendRate updated to: $messageSendRate")
                Replier.restartMessageSender()
            }

        var relayUserId: String
            get() = configValues.relayUserId
            set(value) {
                configValues.relayUserId = value
                saveConfig()
            }

        var relayUserEmail: String
            get() = configValues.relayUserEmail
            set(value) {
                configValues.relayUserEmail = value
                saveConfig()
            }

        var relaySessionId: String
            get() = configValues.relaySessionId
            set(value) {
                configValues.relaySessionId = value
                saveConfig()
            }

        var relaySecret: String
            get() = configValues.relaySecret
            set(value) {
                configValues.relaySecret = value
                saveConfig()
            }

        var routes: List<party.qwer.iris.model.BotRoute>
            get() = configValues.routes
            set(value) {
                configValues.routes = value
                saveConfig()
                println("Routes updated: ${value.size} routes configured")
            }

        var mqttBrokerUrl: String
            get() = configValues.mqttBrokerUrl
            set(value) {
                configValues.mqttBrokerUrl = value
                saveConfig()
                println("MQTT Broker URL updated to: $mqttBrokerUrl")
            }

        fun resolvedRelayUserId(): String {
            val configured = configValues.relayUserId.trim()
            if (configured.isNotEmpty()) {
                return configured
            }
            val bot = configValues.botId
            if (bot > 0) {
                return bot.toString()
            }
            return "iris"
        }

        fun resolvedRelayEmail(): String {
            val configured = configValues.relayUserEmail.trim()
            if (configured.isNotEmpty()) {
                return configured
            }
            return "iris@localhost"
        }

        fun resolvedRelaySessionId(): String {
            val configured = configValues.relaySessionId.trim()
            if (configured.isNotEmpty()) {
                return configured
            }
            return runtimeSessionId
        }
    }
}
