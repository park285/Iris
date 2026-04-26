package party.qwer.iris

import party.qwer.iris.model.RecentMessage
import party.qwer.iris.model.RecentMessagesResponse
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

    fun listRecentMessages(
        chatId: ChatId,
        limit: Int,
        afterId: Long? = null,
        beforeId: Long? = null,
        threadId: Long? = null,
    ): RecentMessagesResponse {
        val rows =
            threadQueries.listRecentMessages(
                chatId = chatId,
                limit = limit,
                afterId = afterId,
                beforeId = beforeId,
                threadId = threadId,
            )
        val messages =
            rows.map { row ->
                val decryptedMessage =
                    if (row.message != null && row.metadata != null) {
                        try {
                            val userId = row.userId.value.takeIf { it > 0L } ?: botId
                            decrypt(row.metadata.enc, row.message, userId)
                        } catch (_: Exception) {
                            row.message
                        }
                    } else {
                        row.message.orEmpty()
                    }
                RecentMessage(
                    id = row.id,
                    chatId = row.chatId.value,
                    userId = row.userId.value,
                    message = decryptedMessage,
                    type = row.type,
                    createdAt = row.createdAt,
                    threadId = row.threadId,
                )
            }
        return RecentMessagesResponse(chatId = chatId.value, messages = messages)
    }
}
