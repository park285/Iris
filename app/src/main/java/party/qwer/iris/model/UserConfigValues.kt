package party.qwer.iris.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import party.qwer.iris.DEFAULT_COMMAND_ROUTE_PREFIXES
import party.qwer.iris.DEFAULT_IMAGE_MESSAGE_TYPE_ROUTES

@Serializable
data class UserConfigValues(
    val botName: String = "Iris",
    val botHttpPort: Int = 3000,
    @SerialName("endpoint")
    val endpoint: String = "",
    @SerialName("webhooks")
    val webhooks: Map<String, String> = emptyMap(),
    @SerialName("webhookToken")
    val webhookToken: String = "",
    @SerialName("botToken")
    val botToken: String = "",
    val dbPollingRate: Long = 100,
    val messageSendRate: Long = 50,
    val messageSendJitterMax: Long = 0,
    val commandRoutePrefixes: Map<String, List<String>> = DEFAULT_COMMAND_ROUTE_PREFIXES,
    val imageMessageTypeRoutes: Map<String, List<String>> = DEFAULT_IMAGE_MESSAGE_TYPE_ROUTES,
)
