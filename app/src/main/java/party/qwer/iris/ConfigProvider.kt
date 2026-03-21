package party.qwer.iris

interface ConfigProvider {
    val botId: Long
    val botName: String
    val botSocketPort: Int
    val botToken: String
    val webhookToken: String
    val dbPollingRate: Long
    val messageSendRate: Long

    fun webhookEndpointFor(route: String): String
}
