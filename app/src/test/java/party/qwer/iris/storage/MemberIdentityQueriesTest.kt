package party.qwer.iris.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MemberIdentityQueriesTest {
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

    private val noopDecrypt: (Int, String, Long) -> String = { _, s, _ -> s }

    @Test
    fun `loadOpenMembers returns typed rows`() {
        val queries = MemberIdentityQueries(
            client { sql, _ ->
                if (sql.contains("open_chat_member")) {
                    listOf(
                        row(
                            "user_id" to "1",
                            "nickname" to "Alice",
                            "link_member_type" to "4",
                            "profile_image_url" to "http://img",
                            "enc" to "0",
                        ),
                    )
                } else {
                    emptyList()
                }
            },
            noopDecrypt,
            botId = 99L,
        )

        val members = queries.loadOpenMembers(LinkId(10L))
        assertEquals(1, members.size)
        assertEquals(1L, members[0].userId)
        assertEquals("Alice", members[0].nickname)
        assertEquals(4, members[0].linkMemberType)
        assertEquals("http://img", members[0].profileImageUrl)
    }

    @Test
    fun `resolveOpenNickname decrypts when enc is positive`() {
        val decrypt: (Int, String, Long) -> String = { enc, raw, _ -> "dec($enc:$raw)" }
        val queries = MemberIdentityQueries(
            client { sql, _ ->
                if (sql.contains("open_chat_member")) {
                    listOf(row("nickname" to "encrypted", "enc" to "3"))
                } else {
                    emptyList()
                }
            },
            decrypt,
            botId = 99L,
        )

        assertEquals("dec(3:encrypted)", queries.resolveOpenNickname(UserId(1L), LinkId(10L)))
    }

    @Test
    fun `resolveOpenNickname returns null when no row`() {
        val queries = MemberIdentityQueries(
            client { _, _ -> emptyList() },
            noopDecrypt,
            botId = 99L,
        )

        assertNull(queries.resolveOpenNickname(UserId(1L), LinkId(10L)))
    }

    @Test
    fun `resolveFriendName returns decrypted name`() {
        val decrypt: (Int, String, Long) -> String = { enc, raw, _ -> "dec($enc:$raw)" }
        val queries = MemberIdentityQueries(
            client { sql, _ ->
                if (sql.contains("friends")) {
                    listOf(row("name" to "Bob", "enc" to "2"))
                } else {
                    emptyList()
                }
            },
            decrypt,
            botId = 99L,
        )

        assertEquals("dec(2:Bob)", queries.resolveFriendName(UserId(5L)))
    }

    @Test
    fun `resolveFriendName returns null for missing friend`() {
        val queries = MemberIdentityQueries(
            client { _, _ -> emptyList() },
            noopDecrypt,
            botId = 99L,
        )

        assertNull(queries.resolveFriendName(UserId(5L)))
    }

    @Test
    fun `loadFriendsBatch returns map of userId to name`() {
        val queries = MemberIdentityQueries(
            client { sql, _ ->
                if (sql.contains("friends")) {
                    listOf(
                        row("id" to "1", "name" to "Alice", "enc" to "0"),
                        row("id" to "2", "name" to "Bob", "enc" to "0"),
                    )
                } else {
                    emptyList()
                }
            },
            noopDecrypt,
            botId = 99L,
        )

        val result = queries.loadFriendsBatch(listOf(UserId(1L), UserId(2L)))
        assertEquals("Alice", result[UserId(1L)])
        assertEquals("Bob", result[UserId(2L)])
    }

    @Test
    fun `loadOpenNicknamesBatch returns map of userId to nickname`() {
        val queries = MemberIdentityQueries(
            client { sql, _ ->
                if (sql.contains("open_chat_member")) {
                    listOf(
                        row("user_id" to "1", "nickname" to "OpenAlice", "enc" to "0"),
                        row("user_id" to "2", "nickname" to "OpenBob", "enc" to "0"),
                    )
                } else {
                    emptyList()
                }
            },
            noopDecrypt,
            botId = 99L,
        )

        val result = queries.loadOpenNicknamesBatch(LinkId(10L), listOf(UserId(1L), UserId(2L)))
        assertEquals("OpenAlice", result[UserId(1L)])
        assertEquals("OpenBob", result[UserId(2L)])
    }

    @Test
    fun `resolveSenderRole returns role code`() {
        val queries = MemberIdentityQueries(
            client { sql, _ ->
                if (sql.contains("link_member_type")) {
                    listOf(row("link_member_type" to "4"))
                } else {
                    emptyList()
                }
            },
            noopDecrypt,
            botId = 99L,
        )

        assertEquals(4, queries.resolveSenderRole(UserId(1L), LinkId(10L)))
    }

    @Test
    fun `resolveSenderRole returns null for missing member`() {
        val queries = MemberIdentityQueries(
            client { _, _ -> emptyList() },
            noopDecrypt,
            botId = 99L,
        )

        assertNull(queries.resolveSenderRole(UserId(1L), LinkId(10L)))
    }
}
