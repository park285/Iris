package party.qwer.iris.storage

class ThreadQueries(
    private val db: SqlClient,
) {
    companion object {
        private const val MAX_THREADS = 20
    }

    data class ThreadRow(
        val threadId: Long,
        val messageCount: Int,
        val lastActiveAt: Long,
        val originMessage: String?,
        val originUserId: Long?,
        val originV: String?,
    )

    fun listThreads(chatId: ChatId): List<ThreadRow> =
        db.query(
            QuerySpec(
                sql =
                    """
                    SELECT
                        t.thread_id,
                        t.msg_count,
                        t.last_active,
                        o.message AS origin_message,
                        o.user_id AS origin_user_id,
                        o.v AS origin_v
                    FROM (
                        SELECT thread_id, COUNT(*) AS msg_count, MAX(created_at) AS last_active
                        FROM chat_logs
                        WHERE chat_id = ? AND thread_id IS NOT NULL
                        GROUP BY thread_id
                        ORDER BY last_active DESC
                        LIMIT ?
                    ) t
                    LEFT JOIN chat_logs o ON o.id = t.thread_id
                    """.trimIndent(),
                bindArgs =
                    listOf(
                        SqlArg.LongVal(chatId.value),
                        SqlArg.IntVal(MAX_THREADS),
                    ),
                maxRows = MAX_THREADS,
                mapper = { row ->
                    ThreadRow(
                        threadId = row.long("thread_id") ?: 0L,
                        messageCount = row.int("msg_count") ?: 0,
                        lastActiveAt = row.long("last_active") ?: 0L,
                        originMessage = row.string("origin_message"),
                        originUserId = row.long("origin_user_id"),
                        originV = row.string("origin_v"),
                    )
                },
            ),
        )
}
