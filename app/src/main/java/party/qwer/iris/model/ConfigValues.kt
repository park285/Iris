package party.qwer.iris.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ConfigValues(
    var botName: String = "Iris",
    var botHttpPort: Int = 3000,
    @SerialName("endpoint")
    var endpoint: String = "",
    @SerialName("webhookToken")
    var webhookToken: String = "",
    @SerialName("botToken")
    var botToken: String = "",
    var dbPollingRate: Long = 100,
    var messageSendRate: Long = 50,
    var botId: Long = 0L,
)
