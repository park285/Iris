package party.qwer.iris

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MemberRepositoryQueryTest {
    @Test
    fun `observed profile query uses chat_id equality not LIKE`() {
        val executedQueries = mutableListOf<Pair<String, List<String?>>>()
        val repo = MemberRepository(
            executeQuery = { sql, args, _ ->
                executedQueries.add(sql to (args?.toList() ?: emptyList()))
                when {
                    sql.contains("FROM chat_rooms cr") ->
                        listOf(
                            mapOf(
                                "id" to "42",
                                "type" to "DirectChat",
                                "active_members_count" to "1",
                                "link_id" to null,
                                "meta" to null,
                                "members" to "[2]",
                                "link_name" to null,
                                "link_url" to null,
                                "member_limit" to null,
                                "searchable" to null,
                                "bot_role" to null,
                            ),
                        )
                    else -> emptyList()
                }
            },
            decrypt = { _, s, _ -> s },
            botId = 1L,
        )

        repo.listRooms()

        val profileQuery = executedQueries.find { it.first.contains("observed_profiles") }
        requireNotNull(profileQuery) { "expected observed_profiles query to execute" }
        assertFalse(
            profileQuery.first.contains("LIKE"),
            "observed_profiles query should use chat_id = ? not LIKE: ${profileQuery.first}",
        )
        assertEquals(listOf("42"), profileQuery.second)
    }

    @Test
    fun `batch nickname resolution reduces query count`() {
        val queryCount = java.util.concurrent.atomic.AtomicInteger(0)
        val repo = MemberRepository(
            executeQuery = { sql, args, _ ->
                queryCount.incrementAndGet()
                when {
                    sql.contains("open_chat_member") -> {
                        val userIds = args?.drop(1)?.mapNotNull { it?.toLongOrNull() } ?: emptyList()
                        userIds.map { uid ->
                            mapOf("user_id" to uid.toString(), "nickname" to "open-$uid", "enc" to "0")
                        }
                    }
                    sql.contains("friends") -> {
                        val userIds = args?.mapNotNull { it?.toLongOrNull() } ?: emptyList()
                        userIds.map { uid ->
                            mapOf("id" to uid.toString(), "name" to "friend-$uid", "enc" to "0")
                        }
                    }
                    else -> emptyList()
                }
            },
            decrypt = { _, s, _ -> s },
            botId = 1L,
        )

        val result = repo.resolveNicknamesBatch(
            userIds = listOf(1L, 2L, 3L, 4L, 5L),
            linkId = 10L,
            chatId = 42L,
        )

        assertEquals(5, result.size)
        assertEquals("open-1", result[1L])
        assertTrue(queryCount.get() <= 2, "Expected at most 2 queries, got ${queryCount.get()}")
    }

    @Test
    fun `batch nickname falls back through open, friends, observed`() {
        val repo = MemberRepository(
            executeQuery = { sql, _, _ ->
                when {
                    sql.contains("open_chat_member") ->
                        listOf(mapOf("user_id" to "1", "nickname" to "open-nick", "enc" to "0"))
                    sql.contains("friends") ->
                        listOf(mapOf("id" to "2", "name" to "friend-nick", "enc" to "0"))
                    sql.contains("observed_profile_user_links") ->
                        listOf(mapOf("user_id" to "3", "display_name" to "observed-nick"))
                    else -> emptyList()
                }
            },
            decrypt = { _, s, _ -> s },
            botId = 1L,
        )

        val result = repo.resolveNicknamesBatch(
            userIds = listOf(1L, 2L, 3L, 4L),
            linkId = 10L,
            chatId = 42L,
        )

        assertEquals("open-nick", result[1L])
        assertEquals("friend-nick", result[2L])
        assertEquals("observed-nick", result[3L])
        assertEquals("4", result[4L], "Unresolved userId should fall back to string")
    }
}
