package party.qwer.iris.model

import kotlinx.serialization.Serializable

@Serializable
data class ConfigValues(
    var botName: String = "Iris",
    var botHttpPort: Int = 3000,

    // MQTT configuration
    var mqttBrokerUrl: String = "tcp://172.17.0.1:1883",
    var routes: List<BotRoute> = listOf(
        BotRoute("!체스테스트", "iris/bot/cheese"),
        BotRoute("/스자", "iris/bot/suja"),
        BotRoute("!", "iris/bot/holo")  // Catch-all for any "!" prefix
    ),

    var dbPollingRate: Long = 100,
    var messageSendRate: Long = 50,
    var botId: Long = 0L,
    var relayUserId: String = "",
    var relayUserEmail: String = "",
    var relaySessionId: String = "",
    var relaySecret: String = ""
)
