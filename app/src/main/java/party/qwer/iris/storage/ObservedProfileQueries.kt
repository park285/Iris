package party.qwer.iris.storage

class ObservedProfileQueries(
    private val db: SqlClient,
) {
    fun resolveProfileByChatId(chatId: ChatId): ObservedProfileHintRow? =
        try {
            db.querySingle(
                QuerySpec(
                    sql =
                        """
                        SELECT display_name, room_name
                        FROM db3.observed_profiles
                        WHERE chat_id = ?
                        ORDER BY updated_at DESC
                        LIMIT 1
                        """.trimIndent(),
                    bindArgs = listOf(SqlArg.LongVal(chatId.value)),
                    maxRows = 1,
                    mapper = { row ->
                        ObservedProfileHintRow(
                            displayName = row.string("display_name")?.takeIf { it.isNotBlank() },
                            roomName = row.string("room_name")?.takeIf { it.isNotBlank() },
                        )
                    },
                ),
            )
        } catch (_: Exception) {
            null
        }

    fun resolveDisplayNamesBatch(
        userIds: List<Long>,
        chatId: Long?,
    ): Map<Long, String> {
        val orderedIds = userIds.distinct().filter { it > 0L }
        if (orderedIds.isEmpty()) return emptyMap()

        return try {
            val placeholders = orderedIds.joinToString(",") { "?" }
            val (sql, bindArgs) =
                if (chatId != null) {
                    """
                    SELECT user_id, display_name
                    FROM db3.observed_profile_user_links
                    WHERE chat_id = ? AND user_id IN ($placeholders)
                    ORDER BY user_id ASC, updated_at DESC
                    """.trimIndent() to
                        buildList<SqlArg> {
                            add(SqlArg.LongVal(chatId))
                            orderedIds.forEach { add(SqlArg.LongVal(it)) }
                        }
                } else {
                    """
                    SELECT user_id, display_name
                    FROM db3.observed_profile_user_links
                    WHERE user_id IN ($placeholders)
                    ORDER BY user_id ASC, updated_at DESC
                    """.trimIndent() to orderedIds.map { SqlArg.LongVal(it) as SqlArg }
                }

            val result = LinkedHashMap<Long, String>()
            db
                .query(
                    QuerySpec(
                        sql = sql,
                        bindArgs = bindArgs,
                        maxRows = orderedIds.size,
                        mapper = { row ->
                            ObservedProfileLinkRow(
                                userId = row.long("user_id") ?: 0L,
                                displayName = row.string("display_name"),
                            )
                        },
                    ),
                ).forEach { row ->
                    val displayName = row.displayName?.takeIf { it.isNotBlank() } ?: return@forEach
                    result.putIfAbsent(row.userId, displayName)
                }
            result
        } catch (_: Exception) {
            emptyMap()
        }
    }
}
