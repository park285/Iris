package party.qwer.iris.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RoomDirectoryQueriesTest {
    private fun client(handler: (String, List<SqlArg>) -> List<SqlRow>): SqlClient =
        object : SqlClient {
            override fun <T> query(spec: QuerySpec<T>): List<T> {
                val rows = handler(spec.sql, spec.bindArgs)
                return rows.map { spec.mapper(it) }
            }
        }

    private fun row(vararg pairs: Pair<String, String?>): SqlRow {
        val columns = pairs.map { it.first }
        val index = columns.withIndex().associate { (i, name) -> name to i }
        val values = pairs.map { it.second?.let { v -> kotlinx.serialization.json.JsonPrimitive(v) } }
        return SqlRow(index, values)
    }

    @Test
    fun `listAllRooms returns RoomRow list`() {
        val queries =
            RoomDirectoryQueries(
                client { sql, _ ->
                    if (sql.contains("chat_rooms")) {
                        listOf(
                            row(
                                "id" to "42",
                                "type" to "OM",
                                "link_id" to "10",
                                "active_members_count" to "5",
                                "meta" to null,
                                "members" to "[1,2]",
                                "link_name" to "TestRoom",
                                "link_url" to "https://open.kakao.com/o/test",
                                "member_limit" to "100",
                                "searchable" to "1",
                                "bot_role" to "8",
                            ),
                        )
                    } else {
                        emptyList()
                    }
                },
            )

        val rooms = queries.listAllRooms()

        assertEquals(1, rooms.size)
        assertEquals(ChatId(42L), rooms[0].id)
        assertEquals("OM", rooms[0].type)
        assertEquals(LinkId(10L), rooms[0].linkId)
        assertEquals(5, rooms[0].activeMembersCount)
        assertEquals("TestRoom", rooms[0].linkName)
        assertEquals(100, rooms[0].memberLimit)
        assertEquals(8, rooms[0].botRole)
    }

    @Test
    fun `findRoomById returns single room`() {
        val queries =
            RoomDirectoryQueries(
                client { sql, args ->
                    if (sql.contains("chat_rooms") && sql.contains("id = ?")) {
                        val chatId = (args.firstOrNull() as? SqlArg.LongVal)?.value
                        if (chatId == 42L) {
                            listOf(
                                row(
                                    "id" to "42",
                                    "type" to "MultiChat",
                                    "link_id" to null,
                                    "active_members_count" to "3",
                                    "meta" to null,
                                    "members" to "[1,2,3]",
                                    "blinded_member_ids" to "[]",
                                ),
                            )
                        } else {
                            emptyList()
                        }
                    } else {
                        emptyList()
                    }
                },
            )

        val room = queries.findRoomById(ChatId(42L))
        assertNotNull(room)
        assertEquals(ChatId(42L), room.id)
        assertEquals("MultiChat", room.type)
        assertNull(room.linkId)
    }

    @Test
    fun `findRoomById returns null for missing room`() {
        val queries = RoomDirectoryQueries(client { _, _ -> emptyList() })

        assertNull(queries.findRoomById(ChatId(999L)))
    }

    @Test
    fun `resolveLinkId returns link_id from room`() {
        val queries =
            RoomDirectoryQueries(
                client { sql, _ ->
                    if (sql.contains("link_id") && sql.contains("chat_rooms")) {
                        listOf(row("link_id" to "10"))
                    } else {
                        emptyList()
                    }
                },
            )

        assertEquals(LinkId(10L), queries.resolveLinkId(ChatId(42L)))
    }

    @Test
    fun `resolveLinkId returns null when no link`() {
        val queries =
            RoomDirectoryQueries(
                client { sql, _ ->
                    if (sql.contains("link_id") && sql.contains("chat_rooms")) {
                        listOf(row("link_id" to null))
                    } else {
                        emptyList()
                    }
                },
            )

        assertNull(queries.resolveLinkId(ChatId(42L)))
    }

    @Test
    fun `loadOpenLink returns OpenLinkRow`() {
        val queries =
            RoomDirectoryQueries(
                client { sql, _ ->
                    if (sql.contains("open_link")) {
                        listOf(
                            row(
                                "name" to "MyLink",
                                "url" to "https://link.kakao.com/x",
                                "member_limit" to "300",
                                "description" to "desc",
                                "searchable" to "1",
                            ),
                        )
                    } else {
                        emptyList()
                    }
                },
            )

        val link = queries.loadOpenLink(LinkId(10L))
        assertNotNull(link)
        assertEquals("MyLink", link.name)
        assertEquals("https://link.kakao.com/x", link.url)
        assertEquals(300, link.memberLimit)
    }

    @Test
    fun `loadBotCommands returns command list`() {
        val queries =
            RoomDirectoryQueries(
                client { sql, _ ->
                    if (sql.contains("openchat_bot_command")) {
                        listOf(
                            row("name" to "/help", "bot_id" to "100"),
                            row("name" to "/info", "bot_id" to "100"),
                        )
                    } else {
                        emptyList()
                    }
                },
            )

        val commands = queries.loadBotCommands(LinkId(10L))
        assertEquals(2, commands.size)
        assertEquals("/help", commands[0].first)
        assertEquals(100L, commands[0].second)
    }

    @Test
    fun `listAllRoomIds uses dedicated id query`() {
        var executedSql: String? = null
        val queries =
            RoomDirectoryQueries(
                client { sql, _ ->
                    executedSql = sql
                    listOf(
                        row("id" to "42"),
                        row("id" to "77"),
                    )
                },
            )

        val roomIds = queries.listAllRoomIds()

        assertEquals(listOf(ChatId(42L), ChatId(77L)), roomIds)
        assertEquals(
            "SELECT id FROM chat_rooms WHERE id > 0 ORDER BY id",
            executedSql,
        )
    }
}
