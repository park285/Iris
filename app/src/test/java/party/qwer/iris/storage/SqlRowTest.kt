package party.qwer.iris.storage

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SqlRowTest {
    private fun row(vararg pairs: Pair<String, String?>): SqlRow {
        val columns = pairs.map { it.first }
        val index = columns.withIndex().associate { (i, name) -> name to i }
        val values = pairs.map { it.second?.let { v -> JsonPrimitive(v) } }
        return SqlRow(index, values)
    }

    @Test
    fun `string returns column value`() {
        assertEquals("Alice", row("name" to "Alice").string("name"))
    }

    @Test
    fun `string returns null for missing column`() {
        assertNull(row("name" to "Alice").string("age"))
    }

    @Test
    fun `string returns null for null value`() {
        assertNull(row("name" to null).string("name"))
    }

    @Test
    fun `long parses numeric string`() {
        assertEquals(42L, row("id" to "42").long("id"))
    }

    @Test
    fun `long returns null for non-numeric`() {
        assertNull(row("id" to "abc").long("id"))
    }

    @Test
    fun `int parses numeric string`() {
        assertEquals(10, row("count" to "10").int("count"))
    }

    @Test
    fun `int returns null for non-numeric`() {
        assertNull(row("count" to "xyz").int("count"))
    }

    @Test
    fun `long returns null for missing column`() {
        assertNull(row("id" to "42").long("missing"))
    }

    @Test
    fun `multiple columns accessible`() {
        val r = row("id" to "1", "name" to "Bob", "age" to "30")
        assertEquals(1L, r.long("id"))
        assertEquals("Bob", r.string("name"))
        assertEquals(30, r.int("age"))
    }
}
