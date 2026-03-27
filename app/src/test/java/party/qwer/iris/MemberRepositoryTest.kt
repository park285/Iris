package party.qwer.iris

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MemberRepositoryTest {
    @Test
    fun `roleCodeToName maps known codes correctly`() {
        assertEquals(
            "owner",
            party.qwer.iris.model
                .roleCodeToName(1),
        )
        assertEquals(
            "member",
            party.qwer.iris.model
                .roleCodeToName(2),
        )
        assertEquals(
            "admin",
            party.qwer.iris.model
                .roleCodeToName(4),
        )
        assertEquals(
            "bot",
            party.qwer.iris.model
                .roleCodeToName(8),
        )
        assertEquals(
            "member",
            party.qwer.iris.model
                .roleCodeToName(99),
        )
    }

    @Test
    fun `parseJsonLongArray parses member id arrays`() {
        val repo =
            MemberRepository(
                executeQuery = { _, _, _ -> emptyList() },
                decrypt = { _, _, _ -> "" },
                botId = 1L,
            )
        assertEquals(setOf(1L, 2L, 3L), repo.parseJsonLongArray("[1,2,3]"))
        assertEquals(emptySet(), repo.parseJsonLongArray("[]"))
        assertEquals(emptySet(), repo.parseJsonLongArray(null))
        assertEquals(emptySet(), repo.parseJsonLongArray(""))
    }

    @Test
    fun `parsePeriodSeconds converts period strings`() {
        val repo =
            MemberRepository(
                executeQuery = { _, _, _ -> emptyList() },
                decrypt = { _, _, _ -> "" },
                botId = 1L,
            )
        assertEquals(7 * 86400L, repo.parsePeriodSeconds("7d"))
        assertEquals(30 * 86400L, repo.parsePeriodSeconds("30d"))
        assertEquals(null, repo.parsePeriodSeconds("all"))
        assertEquals(7 * 86400L, repo.parsePeriodSeconds("invalid"))
    }

    @Test
    fun `roomStats keeps totals before top member limit`() {
        val chatId = 100L
        val repo =
            MemberRepository(
                executeQuery = { sqlQuery, bindArgs, _ ->
                    when {
                        sqlQuery.contains("FROM chat_logs WHERE chat_id = ? AND created_at >= ?") ->
                            listOf(
                                mapOf("user_id" to "1", "type" to "0", "cnt" to "10", "last_active" to "1000"),
                                mapOf("user_id" to "2", "type" to "0", "cnt" to "7", "last_active" to "900"),
                                mapOf("user_id" to "3", "type" to "0", "cnt" to "3", "last_active" to "800"),
                            )
                        sqlQuery == "SELECT nickname, enc FROM db2.open_chat_member WHERE user_id = ? LIMIT 1" ->
                            listOf(
                                mapOf(
                                    "nickname" to "user-${bindArgs?.get(0)}",
                                    "enc" to "0",
                                ),
                            )
                        else -> emptyList()
                    }
                },
                decrypt = { _, raw, _ -> raw },
                botId = 1L,
            )

        val stats = repo.roomStats(chatId = chatId, period = "all", limit = 2)

        assertEquals(20, stats.totalMessages)
        assertEquals(3, stats.activeMembers)
        assertEquals(2, stats.topMembers.size)
        assertEquals(listOf(1L, 2L), stats.topMembers.map { it.userId })
        assertTrue(stats.topMembers.all { it.userId != 3L || it.messageCount == 3 })
    }
}
