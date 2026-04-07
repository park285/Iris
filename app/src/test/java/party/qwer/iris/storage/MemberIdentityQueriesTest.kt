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
        val queries =
            MemberIdentityQueries(
                client { sql, _ ->
                    if (sql.contains("open_chat_member")) {
                        listOf(
                            row(
                                "user_id" to "1",
                                "nickname" to "Alice",
                                "link_member_type" to "4",
                                "profile_image_url" to "http://img",
                                "full_profile_image_url" to null,
                                "original_profile_image_url" to null,
                                "open_profile_image_url" to null,
                                "open_profile_full_profile_image_url" to null,
                                "open_profile_original_profile_image_url" to null,
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
        assertEquals(UserId(1L), members[0].userId)
        assertEquals("Alice", members[0].nickname)
        assertEquals(4, members[0].linkMemberType)
        assertEquals("http://img", members[0].profileImageUrl)
    }

    @Test
    fun `loadOpenMembers decrypts profile image url when enc is positive`() {
        val decrypt: (Int, String, Long) -> String = { enc, raw, _ -> "dec($enc:$raw)" }
        val queries =
            MemberIdentityQueries(
                client { sql, _ ->
                    if (sql.contains("open_chat_member")) {
                        listOf(
                            row(
                                "user_id" to "1",
                                "nickname" to "Alice",
                                "link_member_type" to "4",
                                "profile_image_url" to "ciphertext-s",
                                "full_profile_image_url" to "ciphertext-l",
                                "original_profile_image_url" to "ciphertext-o",
                                "open_profile_image_url" to null,
                                "open_profile_full_profile_image_url" to null,
                                "open_profile_original_profile_image_url" to null,
                                "enc" to "31",
                            ),
                        )
                    } else {
                        emptyList()
                    }
                },
                decrypt,
                botId = 99L,
            )

        val members = queries.loadOpenMembers(LinkId(10L))
        assertEquals(1, members.size)
        assertEquals("dec(31:ciphertext-o)", members[0].profileImageUrl)
    }

    @Test
    fun `loadOpenMembers prefers open profile image urls when present`() {
        val queries =
            MemberIdentityQueries(
                client { sql, _ ->
                    if (sql.contains("open_chat_member")) {
                        listOf(
                            row(
                                "user_id" to "1",
                                "nickname" to "Alice",
                                "link_member_type" to "4",
                                "linked_profile_nickname" to null,
                                "linked_profile_enc" to null,
                                "profile_image_url" to "ciphertext-s",
                                "full_profile_image_url" to "ciphertext-l",
                                "original_profile_image_url" to "ciphertext-o",
                                "open_profile_image_url" to "https://example.com/s.jpg",
                                "open_profile_full_profile_image_url" to "https://example.com/l.jpg",
                                "open_profile_original_profile_image_url" to "https://example.com/o.jpg",
                                "enc" to "31",
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
        assertEquals("https://example.com/o.jpg", members[0].profileImageUrl)
    }

    @Test
    fun `loadOpenMembers keeps room nickname when linked profile nickname is also present`() {
        val queries =
            MemberIdentityQueries(
                client { sql, _ ->
                    if (sql.contains("open_chat_member")) {
                        listOf(
                            row(
                                "user_id" to "1",
                                "nickname" to "RoomAlice",
                                "link_member_type" to "4",
                                "linked_profile_nickname" to "ProfileAlice",
                                "linked_profile_enc" to "0",
                                "profile_image_url" to null,
                                "full_profile_image_url" to null,
                                "original_profile_image_url" to null,
                                "open_profile_image_url" to null,
                                "open_profile_full_profile_image_url" to null,
                                "open_profile_original_profile_image_url" to null,
                                "enc" to "31",
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
        assertEquals("RoomAlice", members[0].nickname)
        assertEquals(31, members[0].enc)
    }

    @Test
    fun `resolveOpenNickname decrypts when enc is positive`() {
        val decrypt: (Int, String, Long) -> String = { enc, raw, _ -> "dec($enc:$raw)" }
        val queries =
            MemberIdentityQueries(
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
        val queries =
            MemberIdentityQueries(
                client { _, _ -> emptyList() },
                noopDecrypt,
                botId = 99L,
            )

        assertNull(queries.resolveOpenNickname(UserId(1L), LinkId(10L)))
    }

    @Test
    fun `resolveOpenNickname keeps room nickname when linked profile nickname is also present`() {
        val queries =
            MemberIdentityQueries(
                client { sql, _ ->
                    if (sql.contains("open_chat_member")) {
                        listOf(
                            row(
                                "nickname" to "RoomAlice",
                                "enc" to "31",
                                "linked_profile_nickname" to "ProfileAlice",
                                "linked_profile_enc" to "0",
                            ),
                        )
                    } else {
                        emptyList()
                    }
                },
                noopDecrypt,
                botId = 99L,
            )

        assertEquals("RoomAlice", queries.resolveOpenNickname(UserId(1L), LinkId(10L)))
    }

    @Test
    fun `resolveFriendName returns decrypted name`() {
        val decrypt: (Int, String, Long) -> String = { enc, raw, _ -> "dec($enc:$raw)" }
        val queries =
            MemberIdentityQueries(
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
        val queries =
            MemberIdentityQueries(
                client { _, _ -> emptyList() },
                noopDecrypt,
                botId = 99L,
            )

        assertNull(queries.resolveFriendName(UserId(5L)))
    }

    @Test
    fun `loadFriendsBatch returns map of userId to name`() {
        val queries =
            MemberIdentityQueries(
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
        val queries =
            MemberIdentityQueries(
                client { sql, _ ->
                    if (sql.contains("open_chat_member")) {
                        listOf(
                            row("user_id" to "1", "nickname" to "OpenAlice", "enc" to "0", "linked_profile_nickname" to null, "linked_profile_enc" to null),
                            row("user_id" to "2", "nickname" to "OpenBob", "enc" to "0", "linked_profile_nickname" to null, "linked_profile_enc" to null),
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
    fun `loadOpenNicknamesBatch keeps room nickname when linked profile nickname is also present`() {
        val queries =
            MemberIdentityQueries(
                client { sql, _ ->
                    if (sql.contains("open_chat_member")) {
                        listOf(
                            row("user_id" to "1", "nickname" to "RoomAlice", "enc" to "31", "linked_profile_nickname" to "ProfileAlice", "linked_profile_enc" to "0"),
                        )
                    } else {
                        emptyList()
                    }
                },
                noopDecrypt,
                botId = 99L,
            )

        val result = queries.loadOpenNicknamesBatch(LinkId(10L), listOf(UserId(1L)))
        assertEquals("RoomAlice", result[UserId(1L)])
    }

    @Test
    fun `loadOpenMembers falls back to linked profile nickname when room nickname is blank`() {
        val queries =
            MemberIdentityQueries(
                client { sql, _ ->
                    if (sql.contains("open_chat_member")) {
                        listOf(
                            row(
                                "user_id" to "1",
                                "nickname" to "",
                                "link_member_type" to "4",
                                "linked_profile_nickname" to "ProfileAlice",
                                "linked_profile_enc" to "0",
                                "profile_image_url" to null,
                                "full_profile_image_url" to null,
                                "original_profile_image_url" to null,
                                "open_profile_image_url" to null,
                                "open_profile_full_profile_image_url" to null,
                                "open_profile_original_profile_image_url" to null,
                                "enc" to "31",
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
        assertEquals("ProfileAlice", members[0].nickname)
        assertEquals(0, members[0].enc)
    }

    @Test
    fun `resolveOpenNickname falls back to linked profile nickname when room nickname is blank`() {
        val queries =
            MemberIdentityQueries(
                client { sql, _ ->
                    if (sql.contains("open_chat_member")) {
                        listOf(
                            row(
                                "nickname" to "",
                                "enc" to "31",
                                "linked_profile_nickname" to "ProfileAlice",
                                "linked_profile_enc" to "0",
                            ),
                        )
                    } else {
                        emptyList()
                    }
                },
                noopDecrypt,
                botId = 99L,
            )

        assertEquals("ProfileAlice", queries.resolveOpenNickname(UserId(1L), LinkId(10L)))
    }

    @Test
    fun `loadOpenNicknamesBatch falls back to linked profile nickname when room nickname is blank`() {
        val queries =
            MemberIdentityQueries(
                client { sql, _ ->
                    if (sql.contains("open_chat_member")) {
                        listOf(
                            row("user_id" to "1", "nickname" to "", "enc" to "31", "linked_profile_nickname" to "ProfileAlice", "linked_profile_enc" to "0"),
                        )
                    } else {
                        emptyList()
                    }
                },
                noopDecrypt,
                botId = 99L,
            )

        val result = queries.loadOpenNicknamesBatch(LinkId(10L), listOf(UserId(1L)))
        assertEquals("ProfileAlice", result[UserId(1L)])
    }

    @Test
    fun `resolveSenderRole returns role code`() {
        val queries =
            MemberIdentityQueries(
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
        val queries =
            MemberIdentityQueries(
                client { _, _ -> emptyList() },
                noopDecrypt,
                botId = 99L,
            )

        assertNull(queries.resolveSenderRole(UserId(1L), LinkId(10L)))
    }
}
