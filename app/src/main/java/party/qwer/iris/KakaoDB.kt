package party.qwer.iris

class KakaoDB(
    private val config: ConfigManager,
) : ChatLogRepository,
    ProfileRepository {
    private val metadataStore = IrisMetadataStore()
    private val runtime = KakaoDbRuntime(config)
    private val identityReader = KakaoIdentityReader(runtime, config)
    private val adminQueryService = AdminQueryService(runtime)
    private val chatLogReader = KakaoChatLogReader(runtime, identityReader, adminQueryService)

    override fun pollChatLogsAfter(
        afterLogId: Long,
        limit: Int,
    ): List<ChatLogEntry> = chatLogReader.pollChatLogsAfter(afterLogId, limit)

    override fun resolveSenderName(userId: Long): String = chatLogReader.resolveSenderName(userId)

    override fun resolveRoomMetadata(chatId: Long): RoomMetadata = chatLogReader.resolveRoomMetadata(chatId)

    override fun latestLogId(): Long = chatLogReader.latestLogId()

    override fun executeQuery(
        sqlQuery: String,
        bindArgs: Array<String?>?,
        maxRows: Int,
    ): QueryExecutionResult = chatLogReader.executeQuery(sqlQuery, bindArgs, maxRows)

    override fun upsertObservedProfile(identity: KakaoNotificationIdentity) = metadataStore.upsertObservedProfile(identity)

    override fun learnObservedProfileUserMappings(
        chatId: Long,
        userDisplayNames: Map<Long, String>,
    ) = metadataStore.learnObservedProfileUserMappings(chatId, userDisplayNames)

    override fun learnFromTimestampCorrelation(
        chatId: Long,
        userId: Long,
        messageCreatedAtMs: Long,
    ) = metadataStore.learnFromTimestampCorrelation(chatId, userId, messageCreatedAtMs)

    override fun resolveObservedDisplayName(
        userId: Long,
        chatId: Long?,
    ): String? = metadataStore.resolveObservedDisplayName(userId, chatId)

    fun closeConnection() {
        metadataStore.close()
        runtime.close()
        IrisLogger.info("Database connection closed.")
    }

    data class ChatLogEntry(
        val id: Long,
        val chatLogId: String? = null,
        val chatId: Long,
        val userId: Long,
        val message: String,
        val metadata: String,
        val createdAt: String?,
        val messageType: String? = null,
        val threadId: String? = null,
        val supplement: String? = null,
        val attachment: String? = null,
    )

    data class RoomMetadata(
        val type: String = "",
        val linkId: String = "",
    )

    companion object {
        const val DEFAULT_QUERY_RESULT_LIMIT = 500
        const val DEFAULT_POLL_BATCH_SIZE = 100
    }
}

internal fun android.database.Cursor.getOptionalString(index: Int): String? = index.takeIf { it >= 0 }?.let { getString(it) }
