package party.qwer.iris

import java.nio.file.Files
import java.util.concurrent.ScheduledExecutorService
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImageDeleterCoroutineTest {
    @Test
    fun `does not retain a scheduled executor implementation`() {
        val hasSchedulerField =
            ImageDeleter::class.java.declaredFields.any { field ->
                ScheduledExecutorService::class.java.isAssignableFrom(field.type)
            }

        assertFalse(hasSchedulerField)
    }

    @Test
    fun `deletes expired files while keeping fresh files`() {
        val tempDir = Files.createTempDirectory("image-deleter-test")
        val staleFile = tempDir.resolve("stale.jpg").apply { writeText("stale") }.toFile()
        val freshFile = tempDir.resolve("fresh.jpg").apply { writeText("fresh") }.toFile()
        val retentionMillis = 500L
        staleFile.setLastModified(System.currentTimeMillis() - retentionMillis - 1_000L)

        val deleter = ImageDeleter(tempDir.toString(), deletionInterval = 50L, retentionMillis = retentionMillis)
        try {
            deleter.startDeletion()
            waitUntil(2_000L) { !staleFile.exists() }

            assertFalse(staleFile.exists())
            assertTrue(freshFile.exists())
        } finally {
            deleter.stopDeletion()
            freshFile.delete()
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `stops deleting after shutdown`() {
        val tempDir = Files.createTempDirectory("image-deleter-stop-test")
        val retentionMillis = 100L
        val deleter = ImageDeleter(tempDir.toString(), deletionInterval = 50L, retentionMillis = retentionMillis)

        try {
            deleter.startDeletion()
            Thread.sleep(150)
            deleter.stopDeletion()

            val fileAfterStop = tempDir.resolve("after-stop.jpg").apply { writeText("after") }.toFile()
            fileAfterStop.setLastModified(System.currentTimeMillis() - retentionMillis - 1_000L)
            Thread.sleep(250)

            assertTrue(fileAfterStop.exists())
            assertEquals(1, tempDir.toFile().listFiles()?.size)
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    private fun waitUntil(
        timeoutMs: Long,
        predicate: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) {
                return
            }
            Thread.sleep(25)
        }
        assertTrue(predicate())
    }
}
