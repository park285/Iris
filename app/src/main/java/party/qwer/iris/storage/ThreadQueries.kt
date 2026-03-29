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
     * 원본 메시지는 MIN(id) 서브쿼리로 thread를 시작한 첫 번째 행에서 가져온다.
     */
    fun listThreads(chatId: ChatId): List<ThreadRow> =
        db.query(
            QuerySpec(
                sql =
                    """
                    SELECT
                        cl.thread_id,
                        COUNT(*) AS message_count,
                        MAX(cl.created_at) AS last_active_at,
                        origin.message AS origin_message,
                        origin.user_id AS origin_user_id,
                        origin.v AS origin_v
                    FROM chat_logs cl
                    LEFT JOIN chat_logs origin
                        ON origin.id = (
                            SELECT MIN(id) FROM chat_logs
                            WHERE chat_id = ? AND thread_id = cl.thread_id
                        )
                    WHERE cl.chat_id = ? AND cl.thread_id IS NOT NULL
                    GROUP BY cl.thread_id
                    ORDER BY last_active_at DESC
                    LIMIT ?
                    """.trimIndent(),
                bindArgs =
                    listOf(
                        SqlArg.LongVal(chatId.value),
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
                        originUserId = row.long("origin_user_id"),
                        originV = row.string("origin_v"),
                    )
                },
            ),
        )
}
