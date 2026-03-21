package party.qwer.iris

interface ChatLogRepository {
    fun pollChatLogsAfter(
        afterLogId: Long,
        limit: Int = KakaoDB.DEFAULT_POLL_BATCH_SIZE,
    ): List<KakaoDB.ChatLogEntry>

    fun resolveSenderName(userId: Long): String

    fun resolveRoomMetadata(chatId: Long): KakaoDB.RoomMetadata

    fun latestLogId(): Long

    fun executeQuery(
        sqlQuery: String,
        bindArgs: Array<String?>?,
        maxRows: Int = KakaoDB.DEFAULT_QUERY_RESULT_LIMIT,
    ): List<Map<String, String?>>
}
