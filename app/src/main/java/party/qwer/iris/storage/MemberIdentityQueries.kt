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
                    SELECT
                        om.user_id,
                        om.nickname,
                        om.link_member_type,
                        (
                            SELECT pom.nickname
                            FROM db2.open_chat_member pom
                            WHERE pom.link_id = om.profile_link_id
                            LIMIT 1
                        ) AS linked_profile_nickname,
                        (
                            SELECT pom.enc
                            FROM db2.open_chat_member pom
                            WHERE pom.link_id = om.profile_link_id
                            LIMIT 1
                        ) AS linked_profile_enc,
                        om.profile_image_url,
                        om.full_profile_image_url,
                        om.original_profile_image_url,
                        op.profile_image_url AS open_profile_image_url,
                        op.f_profile_image_url AS open_profile_full_profile_image_url,
                        op.o_profile_image_url AS open_profile_original_profile_image_url,
                        om.enc
                    FROM db2.open_chat_member om
                    LEFT JOIN db2.open_profile op ON om.profile_link_id = op.link_id
                    WHERE om.link_id = ?
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
                sql =
                    """
                    SELECT
                        om.nickname,
                        om.enc,
                        (
                            SELECT pom.nickname
                            FROM db2.open_chat_member pom
                            WHERE pom.link_id = om.profile_link_id
                            LIMIT 1
                        ) AS linked_profile_nickname,
                        (
                            SELECT pom.enc
                            FROM db2.open_chat_member pom
                            WHERE pom.link_id = om.profile_link_id
                            LIMIT 1
                        ) AS linked_profile_enc
                    FROM db2.open_chat_member om
                    WHERE om.user_id = ? AND om.link_id = ?
                    LIMIT 1
                    """.trimIndent(),
                bindArgs = listOf(SqlArg.LongVal(userId.value), SqlArg.LongVal(linkId.value)),
                maxRows = 1,
                mapper = { row ->
                    val enc = preferredNicknameEnc(row)
                    val rawNick = preferredNicknameRaw(row)
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
                        SELECT
                            om.user_id,
                            om.nickname,
                            om.enc,
                            (
                                SELECT pom.nickname
                                FROM db2.open_chat_member pom
                                WHERE pom.link_id = om.profile_link_id
                                LIMIT 1
                            ) AS linked_profile_nickname,
                            (
                                SELECT pom.enc
                                FROM db2.open_chat_member pom
                                WHERE pom.link_id = om.profile_link_id
                                LIMIT 1
                            ) AS linked_profile_enc
                        FROM db2.open_chat_member om
                        WHERE om.link_id = ? AND om.user_id IN ($placeholders)
                        """.trimIndent(),
                    bindArgs = bindArgs,
                    maxRows = sorted.size,
                    mapper = { row ->
                        val userId = UserId(row.long("user_id") ?: 0L)
                        val enc = preferredNicknameEnc(row)
                        val rawNick = preferredNicknameRaw(row)
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
            userId = UserId(row.long("user_id") ?: 0L),
            nickname = preferredNicknameRaw(row),
            linkMemberType = row.int("link_member_type") ?: 2,
            profileImageUrl = resolveProfileImageUrl(row),
            enc = preferredNicknameEnc(row),
        )

    private fun preferredNicknameRaw(row: SqlRow): String? =
        row.string("nickname")?.takeIf { it.isNotBlank() }
            ?: row.string("linked_profile_nickname")?.takeIf { it.isNotBlank() }

    private fun preferredNicknameEnc(row: SqlRow): Int =
        if (!row.string("nickname").isNullOrBlank()) {
            row.int("enc") ?: 0
        } else {
            row.int("linked_profile_enc") ?: 0
        }

    private fun resolveProfileImageUrl(row: SqlRow): String? {
        openProfileImageUrl(row)?.let { return it }

        val enc = row.int("enc") ?: 0
        for (column in listOf("original_profile_image_url", "full_profile_image_url", "profile_image_url")) {
            val raw = row.string(column)?.trim().orEmpty()
            if (raw.isEmpty()) {
                continue
            }

            return if (enc > 0) decrypt(enc, raw, botId) else raw
        }

        return null
    }

    private fun openProfileImageUrl(row: SqlRow): String? {
        for (column in listOf("open_profile_original_profile_image_url", "open_profile_full_profile_image_url", "open_profile_image_url")) {
            val raw = row.string(column)?.trim().orEmpty()
            if (raw.isNotEmpty()) {
                return raw
            }
        }

        return null
    }
}
