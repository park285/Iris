package party.qwer.iris.storage

class RoomStatsQueries(
    private val db: SqlClient,
) {
    companion object {
        private const val STATS_ROW_LIMIT = 50_000
    }

    fun loadMemberActivity(
        chatId: ChatId,
        memberIds: List<Long>,
    ): List<MemberActivityRow> {
        if (memberIds.isEmpty()) return emptyList()
        val placeholders = memberIds.joinToString(", ") { "?" }
        val bindArgs =
            buildList<SqlArg> {
                add(SqlArg.LongVal(chatId.value))
                memberIds.forEach { add(SqlArg.LongVal(it)) }
            }
        return db.query(
            QuerySpec(
                sql =
                    """
                    SELECT user_id, COUNT(*) as message_count, MAX(created_at) as last_active
                    FROM chat_logs
                    WHERE chat_id = ? AND user_id IN ($placeholders)
                    GROUP BY user_id
                    ORDER BY last_active DESC
                    """.trimIndent(),
                bindArgs = bindArgs,
                maxRows = memberIds.size,
                mapper = ::mapActivityRow,
            ),
        )
    }

    fun loadAllActivity(chatId: ChatId): List<MemberActivityRow> =
        db.query(
            QuerySpec(
                sql =
                    """
                    SELECT user_id, COUNT(*) as message_count, MAX(created_at) as last_active
                    FROM chat_logs
                    WHERE chat_id = ?
                    GROUP BY user_id
                    ORDER BY last_active DESC
                    LIMIT ?
                    """.trimIndent(),
                bindArgs = listOf(SqlArg.LongVal(chatId.value), SqlArg.IntVal(STATS_ROW_LIMIT)),
                maxRows = STATS_ROW_LIMIT,
                mapper = ::mapActivityRow,
            ),
        )

    fun loadTypeCountStats(
        chatId: ChatId,
        from: Long,
    ): List<MessageTypeCountRow> =
        db.query(
            QuerySpec(
                sql =
                    """
                    SELECT user_id, type, COUNT(*) as cnt, MAX(created_at) as last_active
                    FROM chat_logs WHERE chat_id = ? AND created_at >= ?
                    GROUP BY user_id, type ORDER BY cnt DESC LIMIT ?
                    """.trimIndent(),
                bindArgs =
                    listOf(
                        SqlArg.LongVal(chatId.value),
                        SqlArg.LongVal(from),
                        SqlArg.IntVal(STATS_ROW_LIMIT),
                    ),
                maxRows = STATS_ROW_LIMIT,
                mapper = { row ->
                    MessageTypeCountRow(
                        userId = row.long("user_id") ?: 0L,
                        type = row.string("type"),
                        count = row.int("cnt") ?: 0,
                        lastActive = row.long("last_active"),
                    )
                },
            ),
        )

    fun loadMessageLog(
        chatId: ChatId,
        userId: UserId,
        from: Long,
    ): List<MessageLogRow> =
        db.query(
            QuerySpec(
                sql =
                    """
                    SELECT type, created_at FROM chat_logs
                    WHERE chat_id = ? AND user_id = ? AND created_at >= ?
                    ORDER BY created_at ASC LIMIT ?
                    """.trimIndent(),
                bindArgs =
                    listOf(
                        SqlArg.LongVal(chatId.value),
                        SqlArg.LongVal(userId.value),
                        SqlArg.LongVal(from),
                        SqlArg.IntVal(STATS_ROW_LIMIT),
                    ),
                maxRows = STATS_ROW_LIMIT,
                mapper = { row ->
                    MessageLogRow(
                        type = row.string("type"),
                        createdAt = row.long("created_at") ?: 0L,
                    )
                },
            ),
        )

    private fun mapActivityRow(row: SqlRow): MemberActivityRow =
        MemberActivityRow(
            userId = row.long("user_id") ?: 0L,
            messageCount = row.int("message_count") ?: 0,
            lastActive = row.long("last_active"),
        )
}
