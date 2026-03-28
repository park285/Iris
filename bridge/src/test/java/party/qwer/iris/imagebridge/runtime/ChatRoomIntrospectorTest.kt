package party.qwer.iris.imagebridge.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatRoomIntrospectorTest {
    data class FakeRoom(
        val id: Long = 42L,
        val name: String = "TestRoom",
        val members: List<String> = listOf("a", "b"),
        val count: Int = 5,
    )

    @Test
    fun `scans all fields of a simple object`() {
        val result = ChatRoomIntrospector.scan(FakeRoom())
        assertEquals("FakeRoom", result.className.substringAfterLast('.'))
        assertTrue(result.fields.any { it.name == "id" && it.type == "long" })
        assertTrue(result.fields.any { it.name == "name" && it.type.contains("String") })
        assertTrue(result.fields.any { it.name == "members" && it.size == 2 })
        assertTrue(result.fields.any { it.name == "count" && it.type == "int" })
    }

    @Test
    fun `respects max depth`() {
        data class Nested(
            val inner: FakeRoom = FakeRoom(),
        )
        val result = ChatRoomIntrospector.scan(Nested(), maxDepth = 0)
        val innerField = result.fields.first { it.name == "inner" }
        assertTrue(innerField.nested.isEmpty())
    }

    @Test
    fun `scans nested fields at depth 1`() {
        data class Nested(
            val inner: FakeRoom = FakeRoom(),
        )
        val result = ChatRoomIntrospector.scan(Nested(), maxDepth = 1)
        val innerField = result.fields.first { it.name == "inner" }
        assertTrue(innerField.nested.isNotEmpty())
        assertTrue(innerField.nested.any { it.name == "id" })
    }
}
