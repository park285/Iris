package party.qwer.iris

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ImageEncoderTest {
    @Test
    fun `detects png signature`() {
        val pngHeader = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        assertEquals("png", detectImageFileExtension(pngHeader))
    }

    @Test
    fun `detects jpeg signature`() {
        val jpegHeader = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x00)
        assertEquals("jpg", detectImageFileExtension(jpegHeader))
    }

    @Test
    fun `detects webp signature`() {
        val webpHeader =
            byteArrayOf(
                0x52,
                0x49,
                0x46,
                0x46,
                0x00,
                0x00,
                0x00,
                0x00,
                0x57,
                0x45,
                0x42,
                0x50,
            )
        assertEquals("webp", detectImageFileExtension(webpHeader))
    }

    @Test
    fun `detects gif signature`() {
        val gifHeader = byteArrayOf(0x47, 0x49, 0x46, 0x38, 0x39, 0x61)
        assertEquals("gif", detectImageFileExtension(gifHeader))
    }

    @Test
    fun `falls back to generic extension for unknown signature`() {
        assertEquals("img", detectImageFileExtension(byteArrayOf(0x01, 0x02, 0x03)))
    }

    @Test
    fun `decodes valid base64 image payload`() {
        val validSmallPng = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="

        val decoded = decodeBase64Image(validSmallPng)

        assertEquals(70, decoded.size)
    }

    @Test
    fun `rejects invalid base64 image payload`() {
        assertFailsWith<IllegalArgumentException> {
            decodeBase64Image("not-base64!!")
        }
    }

    @Test
    fun `rejects payload exceeding size limit`() {
        val oversized = "A".repeat(MAX_BASE64_IMAGE_PAYLOAD_LENGTH + 1)

        assertFailsWith<IllegalArgumentException> {
            require(oversized.length <= MAX_BASE64_IMAGE_PAYLOAD_LENGTH) { "payload exceeds size limit" }
        }
    }

    @Test
    fun `saveImage writes file and leaves no temp file behind`() {
        val outputDir = Files.createTempDirectory("iris-image-encoder").toFile()
        val imageBytes = byteArrayOf(0x01, 0x02, 0x03)

        val saved = saveImage(imageBytes, outputDir)

        assertTrue(saved.exists())
        assertEquals(imageBytes.toList(), saved.readBytes().toList())
        assertTrue(outputDir.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
        outputDir.deleteRecursively()
    }
}
