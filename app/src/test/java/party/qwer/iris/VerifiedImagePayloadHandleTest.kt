package party.qwer.iris

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class VerifiedImagePayloadHandleTest {
    private companion object {
        private val VALID_TEST_PNG_BYTES =
            byteArrayOf(
                0x89.toByte(),
                0x50,
                0x4E,
                0x47,
                0x0D,
                0x0A,
                0x1A,
                0x0A,
                0x00,
                0x00,
                0x00,
                0x0D,
                0x49,
                0x48,
                0x44,
                0x52,
            )
    }

    @Test
    fun `verifyImagePayloadHandles returns verified image metadata`() {
        val handles = verifyImagePayloadHandles(listOf(VALID_TEST_PNG_BYTES))

        assertEquals(1, handles.size)
        assertEquals(ImageFormat.PNG, handles.single().format)
        assertEquals("image/png", handles.single().contentType)
        assertEquals(VALID_TEST_PNG_BYTES.size.toLong(), handles.single().sizeBytes)
    }

    @Test
    fun `verifyImagePayloadHandles rejects unknown binary payload`() {
        assertFailsWith<IllegalArgumentException> {
            verifyImagePayloadHandles(listOf(byteArrayOf(0x01, 0x02, 0x03)))
        }
    }

    @Test
    fun `verifiedImageHandle spills to file when payload exceeds in memory threshold`() {
        val spillDir = Files.createTempDirectory("iris-verified-handle-test")
        val handle =
            verifiedImageHandle(
                VALID_TEST_PNG_BYTES,
                stagingPolicy =
                    VerifiedImageHandleStagingPolicy(
                        maxInMemoryBytes = 1,
                        spillDirectory = spillDir,
                    ),
            )

        assertTrue(handle is SpillFileVerifiedImagePayloadHandle)
        assertEquals(1, Files.list(spillDir).use { stream -> stream.count() })
        handle.close()
        assertEquals(0, Files.list(spillDir).use { stream -> stream.count() })
    }
}
