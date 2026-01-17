package party.qwer.iris.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ConfigValues(
    var botName: String = "Iris",
    var botHttpPort: Int = 3000,
    // MQ/Redis bridge configuration
    // redroid는 bridge 네트워크에서 실행되므로 docker0 gateway IP 사용
    @SerialName("mqHost")
    var mqHost: String = "172.17.0.1",
    @SerialName("mqPort")
    var mqPort: Int = 1833,
    var dbPollingRate: Long = 100,
    var messageSendRate: Long = 50,
    var messageMaxChars: Int = 100000,
    var botId: Long = 0L,
    var relayUserId: String = "",
    var relayUserEmail: String = "",
    var relaySessionId: String = "",
    var relaySecret: String = "",
)
