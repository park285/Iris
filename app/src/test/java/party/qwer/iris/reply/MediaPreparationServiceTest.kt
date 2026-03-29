package party.qwer.iris.reply

import party.qwer.iris.ImagePayloadMetadata
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MediaPreparationServiceTest {
    private companion object {
        private const val VALID_TEST_PNG_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+cq1cAAAAASUVORK5CYII="
    }

    @Test
    fun `prepares single image and returns prepared images`() {
        val imageDir = Files.createTempDirectory("iris-media-prep").toFile()
        val service =
            MediaPreparationService(
                imageDecoder = { Base64.getMimeDecoder().decode(it) },
                mediaScanner = {},
                imageDir = imageDir,
                imageMediaScanEnabled = false,
            )

        val metadata =
            listOf(
                ImagePayloadMetadata(base64 = VALID_TEST_PNG_BASE64, estimatedDecodedBytes = 67),
            )

        val result = service.prepare(room = 100L, payloadMetadata = metadata)

        assertEquals(100L, result.room)
        assertEquals(1, result.imagePaths.size)
        assertEquals(1, result.files.size)
        assertTrue(result.files[0].exists())
        service.cleanup(result)
        assertFalse(result.files[0].exists())
        imageDir.deleteRecursively()
    }

    @Test
    fun `prepares multiple images preserving order`() {
        val imageDir = Files.createTempDirectory("iris-media-prep-multi").toFile()
        val decodeOrder = AtomicInteger(0)
        val service =
            MediaPreparationService(
                imageDecoder = { payload ->
                    decodeOrder.incrementAndGet()
                    Base64.getMimeDecoder().decode(payload)
                },
                mediaScanner = {},
                imageDir = imageDir,
                imageMediaScanEnabled = false,
            )

        val metadata =
            listOf(
                ImagePayloadMetadata(base64 = VALID_TEST_PNG_BASE64, estimatedDecodedBytes = 67),
                ImagePayloadMetadata(base64 = VALID_TEST_PNG_BASE64, estimatedDecodedBytes = 67),
                ImagePayloadMetadata(base64 = VALID_TEST_PNG_BASE64, estimatedDecodedBytes = 67),
            )

        val result = service.prepare(room = 200L, payloadMetadata = metadata)

        assertEquals(3, result.imagePaths.size)
        assertEquals(3, result.files.size)
        assertEquals(3, decodeOrder.get())
        result.files.forEach { assertTrue(it.exists()) }
        service.cleanup(result)
        result.files.forEach { assertFalse(it.exists()) }
        imageDir.deleteRecursively()
    }

    @Test
    fun `triggers media scan when enabled`() {
        val imageDir = Files.createTempDirectory("iris-media-scan").toFile()
        val scannedFiles = mutableListOf<File>()
        val service =
            MediaPreparationService(
                imageDecoder = { Base64.getMimeDecoder().decode(it) },
                mediaScanner = { file -> scannedFiles.add(file) },
                imageDir = imageDir,
                imageMediaScanEnabled = true,
            )

        val metadata =
            listOf(
                ImagePayloadMetadata(base64 = VALID_TEST_PNG_BASE64, estimatedDecodedBytes = 67),
            )

        val result = service.prepare(room = 300L, payloadMetadata = metadata)

        assertEquals(1, scannedFiles.size)
        service.cleanup(result)
        imageDir.deleteRecursively()
    }

    @Test
    fun `skips media scan when disabled`() {
        val imageDir = Files.createTempDirectory("iris-media-noscan").toFile()
        val scannedFiles = mutableListOf<File>()
        val service =
            MediaPreparationService(
                imageDecoder = { Base64.getMimeDecoder().decode(it) },
                mediaScanner = { file -> scannedFiles.add(file) },
                imageDir = imageDir,
                imageMediaScanEnabled = false,
            )

        val metadata =
            listOf(
                ImagePayloadMetadata(base64 = VALID_TEST_PNG_BASE64, estimatedDecodedBytes = 67),
            )

        val result = service.prepare(room = 400L, payloadMetadata = metadata)

        assertEquals(0, scannedFiles.size)
        service.cleanup(result)
        imageDir.deleteRecursively()
    }

    @Test
    fun `cleans up partial files on decode failure`() {
        val imageDir = Files.createTempDirectory("iris-media-fail").toFile()
        var callCount = 0
        val service =
            MediaPreparationService(
                imageDecoder = { payload ->
                    callCount++
                    if (callCount == 2) {
                        throw RuntimeException("decode fail")
                    }
                    Base64.getMimeDecoder().decode(payload)
                },
                mediaScanner = {},
                imageDir = imageDir,
                imageMediaScanEnabled = false,
            )

        val metadata =
            listOf(
                ImagePayloadMetadata(base64 = VALID_TEST_PNG_BASE64, estimatedDecodedBytes = 67),
                ImagePayloadMetadata(base64 = VALID_TEST_PNG_BASE64, estimatedDecodedBytes = 67),
            )

        assertFailsWith<RuntimeException> {
            service.prepare(room = 500L, payloadMetadata = metadata)
        }

        val remaining = imageDir.listFiles()?.filter { !it.name.endsWith(".tmp") } ?: emptyList()
        assertEquals(0, remaining.size, "partial files should be cleaned up on failure")
        imageDir.deleteRecursively()
    }

    @Test
    fun `creates image directory if it does not exist`() {
        val parentDir = Files.createTempDirectory("iris-media-mkdir").toFile()
        val imageDir = File(parentDir, "nested/images")
        assertFalse(imageDir.exists())

        val service =
            MediaPreparationService(
                imageDecoder = { Base64.getMimeDecoder().decode(it) },
                mediaScanner = {},
                imageDir = imageDir,
                imageMediaScanEnabled = false,
            )

        val metadata =
            listOf(
                ImagePayloadMetadata(base64 = VALID_TEST_PNG_BASE64, estimatedDecodedBytes = 67),
            )

        val result = service.prepare(room = 600L, payloadMetadata = metadata)

        assertTrue(imageDir.exists())
        service.cleanup(result)
        parentDir.deleteRecursively()
    }
}
