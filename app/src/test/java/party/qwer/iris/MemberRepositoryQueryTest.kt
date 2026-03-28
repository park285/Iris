package party.qwer.iris

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

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
}
