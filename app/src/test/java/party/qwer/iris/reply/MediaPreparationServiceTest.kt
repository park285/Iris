package party.qwer.iris.reply

import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MediaPreparationServiceTest {
    private companion object {
        private val VALID_TEST_PNG_BYTES =
            byteArrayOf(
                0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            )
    }

    @Test
    fun `prepares single image and returns prepared images`() {
        val imageDir = Files.createTempDirectory("iris-media-prep").toFile()
        val service =
            MediaPreparationService(
                mediaScanner = {},
                imageDir = imageDir,
                imageMediaScanEnabled = false,
            )

        val result = service.prepare(room = 100L, imageBytesList = listOf(VALID_TEST_PNG_BYTES))

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
        val scannedCount = AtomicInteger(0)
        val service =
            MediaPreparationService(
                mediaScanner = { scannedCount.incrementAndGet() },
                imageDir = imageDir,
                imageMediaScanEnabled = false,
            )

        val result = service.prepare(room = 200L, imageBytesList = List(3) { VALID_TEST_PNG_BYTES })

        assertEquals(3, result.imagePaths.size)
        assertEquals(3, result.files.size)
        assertEquals(0, scannedCount.get())
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
                mediaScanner = { file -> scannedFiles.add(file) },
                imageDir = imageDir,
                imageMediaScanEnabled = true,
            )

        val result = service.prepare(room = 300L, imageBytesList = listOf(VALID_TEST_PNG_BYTES))

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
                mediaScanner = { file -> scannedFiles.add(file) },
                imageDir = imageDir,
                imageMediaScanEnabled = false,
            )

        val result = service.prepare(room = 400L, imageBytesList = listOf(VALID_TEST_PNG_BYTES))

        assertEquals(0, scannedFiles.size)
        service.cleanup(result)
        imageDir.deleteRecursively()
    }

    @Test
    fun `rejects empty payload before preparing files`() {
        val imageDir = Files.createTempDirectory("iris-media-empty").toFile()
        val service =
            MediaPreparationService(
                mediaScanner = {},
                imageDir = imageDir,
                imageMediaScanEnabled = false,
            )

        assertFailsWith<IllegalArgumentException> {
            service.prepare(room = 500L, imageBytesList = listOf(ByteArray(0)))
        }

        val remaining = imageDir.listFiles()?.filter { !it.name.endsWith(".tmp") } ?: emptyList()
        assertEquals(0, remaining.size)
        imageDir.deleteRecursively()
    }

    @Test
    fun `creates image directory if it does not exist`() {
        val parentDir = Files.createTempDirectory("iris-media-mkdir").toFile()
        val imageDir = File(parentDir, "nested/images")
        assertFalse(imageDir.exists())

        val service =
            MediaPreparationService(
                mediaScanner = {},
                imageDir = imageDir,
                imageMediaScanEnabled = false,
            )

        val result = service.prepare(room = 600L, imageBytesList = listOf(VALID_TEST_PNG_BYTES))

        assertTrue(imageDir.exists())
        service.cleanup(result)
        parentDir.deleteRecursively()
    }
}
