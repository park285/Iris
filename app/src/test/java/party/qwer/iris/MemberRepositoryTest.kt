package party.qwer.iris

import kotlin.test.Test
import kotlin.test.assertEquals

class MemberRepositoryTest {

    @Test
    fun `roleCodeToName maps known codes correctly`() {
        assertEquals("owner", party.qwer.iris.model.roleCodeToName(1))
        assertEquals("member", party.qwer.iris.model.roleCodeToName(2))
        assertEquals("admin", party.qwer.iris.model.roleCodeToName(4))
        assertEquals("bot", party.qwer.iris.model.roleCodeToName(8))
        assertEquals("member", party.qwer.iris.model.roleCodeToName(99))
    }

    @Test
    fun `parseJsonLongArray parses member id arrays`() {
        val repo = MemberRepository(
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
        val repo = MemberRepository(
            executeQuery = { _, _, _ -> emptyList() },
            decrypt = { _, _, _ -> "" },
            botId = 1L,
        )
        assertEquals(7 * 86400L, repo.parsePeriodSeconds("7d"))
        assertEquals(30 * 86400L, repo.parsePeriodSeconds("30d"))
        assertEquals(null, repo.parsePeriodSeconds("all"))
        assertEquals(7 * 86400L, repo.parsePeriodSeconds("invalid"))
    }
}
