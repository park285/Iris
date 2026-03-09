package party.qwer.iris.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ConfigValues(
    var botName: String = "Iris",
    var botHttpPort: Int = 3000,
    @SerialName("webhooks")
    var webhooks: Map<String, String> = emptyMap(),
    @SerialName("webhookToken")
    var webhookToken: String = "",
    @SerialName("botToken")
    var botToken: String = "",
    var dbPollingRate: Long = 100,
    var messageSendRate: Long = 50,
    var messageMaxChars: Int = 100000,
    var botId: Long = 0L,
    var relayUserId: String = "",
    var relayUserEmail: String = "",
    var relaySessionId: String = "",
    var relaySecret: String = "",
)
