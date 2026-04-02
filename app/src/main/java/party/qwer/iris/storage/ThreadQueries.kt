package party.qwer.iris.storage

import party.qwer.iris.ThreadOriginMetadataDecoder

class ThreadQueries(
    private val db: SqlClient,
) {
    private val originMetadataDecoder = ThreadOriginMetadataDecoder()

    companion object {
        private const val THREAD_LIST_LIMIT = 20
        private const val RECENT_MESSAGES_MAX_LIMIT = 100
    }

    /**
     * GROUP BY thread_id → MAX(created_at) 최신순 THREAD_LIST_LIMIT개.
     * 원본 메시지는 MIN(id)로 한 번만 조인하여 N+1을 피한다.
     */
    fun listThreads(chatId: ChatId): List<ThreadRow> =
        db.query(
            QuerySpec(
                sql =
                    """
                    WITH thread_stats AS (
                        SELECT
                            thread_id,
                            COUNT(*) AS message_count,
                            MAX(created_at) AS last_active_at,
                            MIN(id) AS origin_id
                        FROM chat_logs
                        WHERE chat_id = ? AND thread_id IS NOT NULL
                        GROUP BY thread_id
                    )
                    SELECT
                        ts.thread_id,
                        ts.message_count,
                        ts.last_active_at,
                        origin.message AS origin_message,
                        origin.user_id AS origin_user_id,
                        origin.v AS origin_v
                    FROM thread_stats ts
                    LEFT JOIN chat_logs origin
                        ON origin.id = ts.origin_id
                    ORDER BY ts.last_active_at DESC
                    LIMIT ?
                    """.trimIndent(),
                bindArgs =
                    listOf(
                        SqlArg.LongVal(chatId.value),
                        SqlArg.IntVal(THREAD_LIST_LIMIT),
                    ),
                maxRows = THREAD_LIST_LIMIT,
                mapper = { row ->
                    ThreadRow(
                        threadId = row.long("thread_id") ?: 0L,
                        messageCount = row.int("message_count") ?: 0,
                        lastActiveAt = row.long("last_active_at"),
                        originMessage = row.string("origin_message"),
                        originUserId = row.long("origin_user_id")?.let(::UserId),
                        originMetadata = originMetadataDecoder.decode(row.string("origin_v")),
                    )
                },
            ),
        )

    fun listRecentMessages(
        chatId: ChatId,
        limit: Int,
    ): List<RecentMessageRow> {
        val safeLimit = limit.coerceIn(1, RECENT_MESSAGES_MAX_LIMIT)
        return db.query(
            QuerySpec(
                sql =
                    """
                    SELECT id, chat_id, user_id, message, type, created_at, thread_id, v
                    FROM chat_logs
                    WHERE chat_id = ?
                    ORDER BY created_at DESC
                    LIMIT ?
                    """.trimIndent(),
                bindArgs =
                    listOf(
                        SqlArg.LongVal(chatId.value),
                        SqlArg.IntVal(safeLimit),
                    ),
                maxRows = safeLimit,
                mapper = { row ->
                    RecentMessageRow(
                        id = row.long("id") ?: 0L,
                        chatId = ChatId(row.long("chat_id") ?: chatId.value),
                        userId = UserId(row.long("user_id") ?: 0L),
                        message = row.string("message"),
                        type = row.int("type") ?: 0,
                        createdAt = row.long("created_at") ?: 0L,
                        threadId = row.long("thread_id"),
                        metadata = originMetadataDecoder.decode(row.string("v")),
                    )
                },
            ),
        )
    }
}
