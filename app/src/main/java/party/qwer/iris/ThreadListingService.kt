package party.qwer.iris

import org.json.JSONObject
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
    fun listThreads(chatId: Long): ThreadListResponse {
        val room = roomDirectory.findRoomById(ChatId(chatId))
        val isOpenChat = room?.type?.startsWith("O") == true
        if (!isOpenChat) {
            return ThreadListResponse(chatId = chatId, threads = emptyList())
        }

        val rows = threadQueries.listThreads(ChatId(chatId))
        val threads =
            rows.map { row ->
                val decryptedOrigin =
                    if (row.originMessage != null && row.originV != null) {
                        try {
                            val enc = JSONObject(row.originV).optInt("enc", 0)
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
        return ThreadListResponse(chatId = chatId, threads = threads)
    }
}
