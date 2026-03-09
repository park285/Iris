package party.qwer.iris.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ConfigResponse(
    @SerialName("bot_name")
    val botName: String,
    @SerialName("web_endpoint")
    val webEndpoint: String,
    @SerialName("bot_http_port")
    val botHttpPort: Int,
    @SerialName("db_polling_rate")
    val dbPollingRate: Long,
    @SerialName("message_send_rate")
    val messageSendRate: Long,
    @SerialName("bot_id")
    val botId: Long,
)
