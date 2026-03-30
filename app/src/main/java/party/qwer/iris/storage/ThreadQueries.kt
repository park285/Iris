package party.qwer.iris.storage

class ThreadQueries(
    private val db: SqlClient,
) {
    companion object {
        // 방당 최대 반환할 스레드 수
        private const val THREAD_LIST_LIMIT = 20
    }

    /**
     * 지정된 chatId의 스레드 목록을 집계해 반환한다.
     * thread_id IS NOT NULL인 행을 GROUP BY thread_id로 집계하고,
     * MAX(created_at) 기준 최신순으로 THREAD_LIST_LIMIT개 반환한다.
     * 원본 메시지는 집계 단계에서 구한 MIN(id)를 이용해 한 번만 조인한다.
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
                        originV = row.string("origin_v"),
                    )
                },
            ),
        )
}
