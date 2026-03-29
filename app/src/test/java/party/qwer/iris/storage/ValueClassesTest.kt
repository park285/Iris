package party.qwer.iris.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ValueClassesTest {
    @Test
    fun `ChatId wraps Long value`() {
        val id = ChatId(42L)
        assertEquals(42L, id.value)
    }

    @Test
    fun `UserId wraps Long value`() {
        val id = UserId(100L)
        assertEquals(100L, id.value)
    }

    @Test
    fun `LinkId wraps Long value`() {
        val id = LinkId(7L)
        assertEquals(7L, id.value)
    }

    @Test
    fun `equal value classes are equal`() {
        assertEquals(ChatId(1L), ChatId(1L))
        assertEquals(UserId(2L), UserId(2L))
        assertEquals(LinkId(3L), LinkId(3L))
    }

    @Test
    fun `different value classes are not equal`() {
        assertNotEquals(ChatId(1L), ChatId(2L))
    }

    @Test
    fun `toString returns underlying value`() {
        assertEquals("ChatId(value=42)", ChatId(42L).toString())
    }
}
