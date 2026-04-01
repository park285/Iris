package party.qwer.iris
import party.qwer.iris.model.ThreadListResponse
import party.qwer.iris.model.ThreadSummary
import party.qwer.iris.storage.ChatId
import party.qwer.iris.storage.ThreadQueries

internal class ThreadListingService(
    private val roomDirectory: party.qwer.iris.storage.RoomDirectoryQueries,
    private val threadQueries: ThreadQueries,
    private val decrypt: (Int, String, Long) -> String,
    private val botId: Long,
) {
    fun listThreads(chatId: ChatId): ThreadListResponse {
        val room = roomDirectory.findRoomById(chatId)
        val isOpenChat = KakaoRoomType.isOpenChat(room?.type)
        if (!isOpenChat) {
            return ThreadListResponse(chatId = chatId.value, threads = emptyList())
        }

        val rows = threadQueries.listThreads(chatId)
        val threads =
            rows.map { row ->
                val decryptedOrigin =
                    if (row.originMessage != null && row.originMetadata != null) {
                        try {
                            val enc = row.originMetadata.enc
                            val userId = row.originUserId?.value ?: botId
                            decrypt(enc, row.originMessage, userId)
                        } catch (_: Exception) {
                            row.originMessage
                        }
                    } else {
                        null
                    }
                ThreadSummary(
                    threadId = row.threadId.toString(),
                    originMessage = decryptedOrigin,
                    messageCount = row.messageCount,
                    lastActiveAt = row.lastActiveAt,
                )
            }
        return ThreadListResponse(chatId = chatId.value, threads = threads)
    }
}
