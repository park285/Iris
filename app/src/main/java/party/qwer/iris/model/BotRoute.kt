package party.qwer.iris.model

import kotlinx.serialization.Serializable

@Serializable
data class BotRoute(
    val prefix: String,
    val mqttTopic: String,
    val enabled: Boolean = true
)
