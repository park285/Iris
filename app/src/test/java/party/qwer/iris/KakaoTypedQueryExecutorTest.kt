package party.qwer.iris

import kotlinx.serialization.json.JsonPrimitive
import party.qwer.iris.nativecore.NativeQueryProjectionCellEnvelope
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KakaoTypedQueryExecutorTest {
    @Test
    fun `project typed query cell preserves current sqlite type behavior`() {
        assertCellProjection(
            envelope = typedQueryCellEnvelope(sqliteType = "NULL"),
            expectedValue = null,
            expectedType = "NULL",
        )
        assertCellProjection(
            envelope = typedQueryCellEnvelope(sqliteType = "INTEGER", longValue = 42L),
            expectedValue = JsonPrimitive(42L),
            expectedType = "INTEGER",
        )
        assertCellProjection(
            envelope = typedQueryCellEnvelope(sqliteType = "FLOAT", doubleValue = 1.25),
            expectedValue = JsonPrimitive(1.25),
            expectedType = "FLOAT",
        )
        assertCellProjection(
            envelope = typedQueryCellEnvelope(sqliteType = "TEXT", textValue = "hello"),
            expectedValue = JsonPrimitive("hello"),
            expectedType = "TEXT",
        )
        assertCellProjection(
            envelope = typedQueryCellEnvelope(sqliteType = "TEXT", textValue = null),
            expectedValue = JsonPrimitive(""),
            expectedType = "TEXT",
        )
        assertCellProjection(
            envelope = typedQueryCellEnvelope(sqliteType = "UNKNOWN", textValue = "raw"),
            expectedValue = JsonPrimitive("raw"),
            expectedType = "UNKNOWN",
        )
        assertCellProjection(
            envelope = typedQueryCellEnvelope(sqliteType = "OTHER", textValue = null),
            expectedValue = JsonPrimitive(""),
            expectedType = "UNKNOWN",
        )
    }

    @Test
    fun `blob envelope uses identical java base64 projection`() {
        val blob = byteArrayOf(0, 1, 2, 127, -128)
        val expectedBase64 = Base64.getEncoder().encodeToString(blob)
        assertEquals("AAECf4A=", expectedBase64)

        val envelope = typedQueryCellEnvelope(sqliteType = "BLOB", blobValue = blob)
        assertEquals(listOf(0, 1, 2, 127, 128), envelope.blob)

        assertCellProjection(
            envelope = envelope,
            expectedValue = JsonPrimitive(expectedBase64),
            expectedType = "BLOB",
        )
    }

    @Test
    fun `empty blob stays explicit and projects to empty base64`() {
        val envelope = typedQueryCellEnvelope(sqliteType = "BLOB", blobValue = byteArrayOf())

        assertEquals(emptyList(), envelope.blob)
        assertCellProjection(
            envelope = envelope,
            expectedValue = JsonPrimitive(""),
            expectedType = "BLOB",
        )
    }

    @Test
    fun `merge observed type preserves current null and unknown behavior`() {
        assertEquals("NULL", mergeObservedType("UNKNOWN", "NULL"))
        assertEquals("INTEGER", mergeObservedType("UNKNOWN", "INTEGER"))
        assertEquals("INTEGER", mergeObservedType("NULL", "INTEGER"))
        assertEquals("FLOAT", mergeObservedType("NULL", "FLOAT"))
        assertEquals("NULL", mergeObservedType("NULL", "NULL"))
        assertEquals("INTEGER", mergeObservedType("INTEGER", "NULL"))
        assertEquals("FLOAT", mergeObservedType("FLOAT", "TEXT"))
        assertEquals("UNKNOWN", mergeObservedType("UNKNOWN", "UNKNOWN"))
    }

    private fun assertCellProjection(
        envelope: NativeQueryProjectionCellEnvelope,
        expectedValue: JsonPrimitive?,
        expectedType: String,
    ) {
        val projected = projectTypedQueryCell(envelope)
        if (expectedValue == null) {
            assertNull(projected.value)
        } else {
            assertEquals(expectedValue, projected.value)
        }
        assertEquals(expectedType, projected.sqliteType)
    }
}
