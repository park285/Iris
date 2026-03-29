package party.qwer.iris.storage

class RoomDirectoryQueries(
    private val db: SqlClient,
) {
    companion object {
        private const val MAX_ROWS = 2000
    }

    data class OpenLinkRow(
        val name: String?,
        val url: String?,
        val memberLimit: Int?,
        val description: String?,
        val searchable: Int?,
    )

    fun listAllRooms(): List<RoomRow> =
        db.query(
            QuerySpec(
                sql =
                    """
                    SELECT cr.id, cr.type, cr.active_members_count, cr.link_id, cr.meta, cr.members,
                           ol.name AS link_name, ol.url AS link_url, ol.member_limit, ol.searchable,
                           op.link_member_type AS bot_role
                    FROM chat_rooms cr
                    LEFT JOIN db2.open_link ol ON cr.link_id = ol.id
                    LEFT JOIN db2.open_profile op ON cr.link_id = op.link_id
                    WHERE (cr.type LIKE 'O%' AND cr.link_id > 0) OR cr.type IN ('MultiChat', 'DirectChat')
                    """.trimIndent(),
                bindArgs = emptyList(),
                maxRows = MAX_ROWS,
                mapper = ::mapRoomRow,
            ),
        )

    fun findRoomById(chatId: ChatId): RoomRow? =
        db.querySingle(
            QuerySpec(
                sql =
                    """
                    SELECT id, type, link_id, active_members_count, meta, members, blinded_member_ids
                    FROM chat_rooms WHERE id = ?
                    """.trimIndent(),
                bindArgs = listOf(SqlArg.LongVal(chatId.value)),
                maxRows = 1,
                mapper = { row ->
                    RoomRow(
                        id = row.long("id") ?: 0L,
                        type = row.string("type"),
                        linkId = row.long("link_id"),
                        activeMembersCount = row.int("active_members_count"),
                        meta = row.string("meta"),
                        members = row.string("members"),
                        blindedMemberIds = row.string("blinded_member_ids"),
                        linkName = null,
                        linkUrl = null,
                        memberLimit = null,
                        searchable = null,
                        botRole = null,
                    )
                },
            ),
        )

    fun findRoomForSnapshot(chatId: ChatId): RoomRow? =
        db.querySingle(
            QuerySpec(
                sql = "SELECT id, type, members, blinded_member_ids, link_id FROM chat_rooms WHERE id = ?",
                bindArgs = listOf(SqlArg.LongVal(chatId.value)),
                maxRows = 1,
                mapper = { row ->
                    RoomRow(
                        id = row.long("id") ?: 0L,
                        type = row.string("type"),
                        linkId = row.long("link_id"),
                        activeMembersCount = null,
                        meta = null,
                        members = row.string("members"),
                        blindedMemberIds = row.string("blinded_member_ids"),
                        linkName = null,
                        linkUrl = null,
                        memberLimit = null,
                        searchable = null,
                        botRole = null,
                    )
                },
            ),
        )

    fun findRoomForListMembers(chatId: ChatId): RoomRow? =
        db.querySingle(
            QuerySpec(
                sql = "SELECT link_id, type, members, active_members_count FROM chat_rooms WHERE id = ?",
                bindArgs = listOf(SqlArg.LongVal(chatId.value)),
                maxRows = 1,
                mapper = { row ->
                    RoomRow(
                        id = chatId.value,
                        type = row.string("type"),
                        linkId = row.long("link_id"),
                        activeMembersCount = row.int("active_members_count"),
                        meta = null,
                        members = row.string("members"),
                        blindedMemberIds = null,
                        linkName = null,
                        linkUrl = null,
                        memberLimit = null,
                        searchable = null,
                        botRole = null,
                    )
                },
            ),
        )

    fun findRoomForInfo(chatId: ChatId): RoomRow? =
        db.querySingle(
            QuerySpec(
                sql = "SELECT id, type, link_id, meta, blinded_member_ids FROM chat_rooms WHERE id = ?",
                bindArgs = listOf(SqlArg.LongVal(chatId.value)),
                maxRows = 1,
                mapper = { row ->
                    RoomRow(
                        id = row.long("id") ?: chatId.value,
                        type = row.string("type"),
                        linkId = row.long("link_id"),
                        activeMembersCount = null,
                        meta = row.string("meta"),
                        members = null,
                        blindedMemberIds = row.string("blinded_member_ids"),
                        linkName = null,
                        linkUrl = null,
                        memberLimit = null,
                        searchable = null,
                        botRole = null,
                    )
                },
            ),
        )

    fun resolveLinkId(chatId: ChatId): LinkId? =
        db.querySingle(
            QuerySpec(
                sql = "SELECT link_id FROM chat_rooms WHERE id = ?",
                bindArgs = listOf(SqlArg.LongVal(chatId.value)),
                maxRows = 1,
                mapper = { row -> row.long("link_id") },
            ),
        )?.let(::LinkId)

    fun loadOpenLink(linkId: LinkId): OpenLinkRow? =
        db.querySingle(
            QuerySpec(
                sql = "SELECT name, url, member_limit, description, searchable FROM db2.open_link WHERE id = ?",
                bindArgs = listOf(SqlArg.LongVal(linkId.value)),
                maxRows = 1,
                mapper = { row ->
                    OpenLinkRow(
                        name = row.string("name"),
                        url = row.string("url"),
                        memberLimit = row.int("member_limit"),
                        description = row.string("description"),
                        searchable = row.int("searchable"),
                    )
                },
            ),
        )

    fun loadBotCommands(linkId: LinkId): List<Pair<String, Long>> =
        db.query(
            QuerySpec(
                sql = "SELECT name, bot_id FROM db2.openchat_bot_command WHERE link_id = ?",
                bindArgs = listOf(SqlArg.LongVal(linkId.value)),
                maxRows = MAX_ROWS,
                mapper = { row ->
                    (row.string("name") ?: "") to (row.long("bot_id") ?: 0L)
                },
            ),
        )

    private fun mapRoomRow(row: SqlRow): RoomRow =
        RoomRow(
            id = row.long("id") ?: 0L,
            type = row.string("type"),
            linkId = row.long("link_id"),
            activeMembersCount = row.int("active_members_count"),
            meta = row.string("meta"),
            members = row.string("members"),
            blindedMemberIds = row.string("blinded_member_ids"),
            linkName = row.string("link_name"),
            linkUrl = row.string("link_url"),
            memberLimit = row.int("member_limit"),
            searchable = row.int("searchable"),
            botRole = row.int("bot_role"),
        )
}
