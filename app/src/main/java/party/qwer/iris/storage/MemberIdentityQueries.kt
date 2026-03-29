package party.qwer.iris.storage

class MemberIdentityQueries(
    private val db: SqlClient,
    private val decrypt: (Int, String, Long) -> String,
    private val botId: Long,
) {
    companion object {
        private const val MAX_ROWS = 2000
    }

    fun loadOpenMembers(linkId: LinkId): List<OpenMemberRow> =
        db.query(
            QuerySpec(
                sql =
                    """
                    SELECT user_id, nickname, link_member_type, profile_image_url, enc
                    FROM db2.open_chat_member WHERE link_id = ?
                    """.trimIndent(),
                bindArgs = listOf(SqlArg.LongVal(linkId.value)),
                maxRows = MAX_ROWS,
                mapper = ::mapOpenMemberRow,
            ),
        )

    fun resolveOpenNickname(
        userId: UserId,
        linkId: LinkId,
    ): String? =
        db.querySingle(
            QuerySpec(
                sql = "SELECT nickname, enc FROM db2.open_chat_member WHERE user_id = ? AND link_id = ? LIMIT 1",
                bindArgs = listOf(SqlArg.LongVal(userId.value), SqlArg.LongVal(linkId.value)),
                maxRows = 1,
                mapper = { row ->
                    val enc = row.int("enc") ?: 0
                    val rawNick = row.string("nickname")
                    if (rawNick != null && enc > 0) decrypt(enc, rawNick, botId) else rawNick
                },
            ),
        )

    fun resolveFriendName(userId: UserId): String? =
        db.querySingle(
            QuerySpec(
                sql = "SELECT name, enc FROM db2.friends WHERE id = ? LIMIT 1",
                bindArgs = listOf(SqlArg.LongVal(userId.value)),
                maxRows = 1,
                mapper = { row ->
                    val enc = row.int("enc") ?: 0
                    val rawName = row.string("name") ?: return@QuerySpec null
                    if (enc > 0) decrypt(enc, rawName, botId) else rawName
                },
            ),
        )

    fun loadOpenNicknamesBatch(
        linkId: LinkId,
        userIds: List<UserId>,
    ): Map<UserId, String?> {
        if (userIds.isEmpty()) return emptyMap()
        val sorted = userIds.sortedBy { it.value }
        val placeholders = sorted.joinToString(", ") { "?" }
        val bindArgs =
            buildList<SqlArg> {
                add(SqlArg.LongVal(linkId.value))
                sorted.forEach { add(SqlArg.LongVal(it.value)) }
            }
        return db
            .query(
                QuerySpec(
                    sql =
                        """
                        SELECT user_id, nickname, enc
                        FROM db2.open_chat_member
                        WHERE link_id = ? AND user_id IN ($placeholders)
                        """.trimIndent(),
                    bindArgs = bindArgs,
                    maxRows = sorted.size,
                    mapper = { row ->
                        val userId = UserId(row.long("user_id") ?: 0L)
                        val enc = row.int("enc") ?: 0
                        val rawNick = row.string("nickname")
                        userId to if (rawNick != null && enc > 0) decrypt(enc, rawNick, botId) else rawNick
                    },
                ),
            ).toMap()
    }

    fun loadFriendsBatch(userIds: List<UserId>): Map<UserId, String> {
        if (userIds.isEmpty()) return emptyMap()
        val placeholders = userIds.joinToString(",") { "?" }
        return db
            .query(
                QuerySpec(
                    sql = "SELECT id, name, enc FROM db2.friends WHERE id IN ($placeholders)",
                    bindArgs = userIds.map { SqlArg.LongVal(it.value) },
                    maxRows = userIds.size,
                    mapper = { row ->
                        val userId = UserId(row.long("id") ?: 0L)
                        val enc = row.int("enc") ?: 0
                        val rawName = row.string("name") ?: return@QuerySpec null
                        userId to if (enc > 0) decrypt(enc, rawName, botId) else rawName
                    },
                ),
            ).filterNotNull()
            .toMap()
    }

    fun resolveSenderRole(
        userId: UserId,
        linkId: LinkId,
    ): Int? =
        db.querySingle(
            QuerySpec(
                sql = "SELECT link_member_type FROM db2.open_chat_member WHERE user_id = ? AND link_id = ? LIMIT 1",
                bindArgs = listOf(SqlArg.LongVal(userId.value), SqlArg.LongVal(linkId.value)),
                maxRows = 1,
                mapper = { row -> row.int("link_member_type") },
            ),
        )

    fun decryptNickname(
        enc: Int,
        raw: String,
    ): String = if (enc > 0) decrypt(enc, raw, botId) else raw

    private fun mapOpenMemberRow(row: SqlRow): OpenMemberRow =
        OpenMemberRow(
            userId = row.long("user_id") ?: 0L,
            nickname = row.string("nickname"),
            linkMemberType = row.int("link_member_type") ?: 2,
            profileImageUrl = row.string("profile_image_url"),
            enc = row.int("enc") ?: 0,
        )
}
