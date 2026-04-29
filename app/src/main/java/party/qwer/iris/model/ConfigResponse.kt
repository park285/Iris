package party.qwer.iris.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ConfigState(
    @SerialName("bot_name")
    val botName: String,
    @SerialName("web_endpoint")
    val webEndpoint: String,
    @SerialName("webhooks")
    val webhooks: Map<String, String>,
    @SerialName("bot_http_port")
    val botHttpPort: Int,
    @SerialName("db_polling_rate")
    val dbPollingRate: Long,
    @SerialName("message_send_rate")
    val messageSendRate: Long,
    @SerialName("command_route_prefixes")
    val commandRoutePrefixes: Map<String, List<String>> = emptyMap(),
    @SerialName("image_message_type_routes")
    val imageMessageTypeRoutes: Map<String, List<String>> = emptyMap(),
    @SerialName("event_type_routes")
    val eventTypeRoutes: Map<String, List<String>> = emptyMap(),
)

@Serializable
data class ConfigDiscoveredState(
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
    val user: ConfigState,
    val applied: ConfigState,
    val discovered: ConfigDiscoveredState,
    @SerialName("pending_restart")
    val pendingRestart: ConfigPendingRestart,
)
