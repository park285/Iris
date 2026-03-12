package party.qwer.iris.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ConfigState(
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

@Serializable
data class ConfigPendingRestart(
    val required: Boolean,
    val fields: List<String>,
)

@Serializable
data class ConfigResponse(
    val snapshot: ConfigState,
    val effective: ConfigState,
    @SerialName("pending_restart")
    val pendingRestart: ConfigPendingRestart,
    @SerialName("bot_name")
    val botName: String = effective.botName,
    @SerialName("web_endpoint")
    val webEndpoint: String = effective.webEndpoint,
    @SerialName("bot_http_port")
    val botHttpPort: Int = effective.botHttpPort,
    @SerialName("db_polling_rate")
    val dbPollingRate: Long = effective.dbPollingRate,
    @SerialName("message_send_rate")
    val messageSendRate: Long = effective.messageSendRate,
    @SerialName("bot_id")
    val botId: Long = effective.botId,
)
