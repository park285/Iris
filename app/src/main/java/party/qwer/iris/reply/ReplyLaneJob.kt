package party.qwer.iris.reply

internal interface ReplyLaneJob {
    val requestId: String?

    suspend fun prepare() {}

    suspend fun abort() {}

    suspend fun send()
}
