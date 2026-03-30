package party.qwer.iris

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.util.concurrent.ScheduledExecutorService
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
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
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `deletes expired files while keeping fresh files`() {
        val tempDir = Files.createTempDirectory("image-deleter-test")
        val staleFile = tempDir.resolve("stale.jpg").apply { writeText("stale") }.toFile()
        val freshFile = tempDir.resolve("fresh.jpg").apply { writeText("fresh") }.toFile()
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val retentionMillis = 500L
            val nowMs = 1_000L
            staleFile.setLastModified(0L)
            freshFile.setLastModified(750L)

            val deleter =
                ImageDeleter(
                    tempDir.toString(),
                    deletionInterval = 50L,
                    retentionMillis = retentionMillis,
                    dispatcher = dispatcher,
                    clock = { nowMs },
                )
            try {
                deleter.startDeletion()
                runCurrent()

                assertFalse(staleFile.exists())
                assertTrue(freshFile.exists())
            } finally {
                deleter.stopDeletionSuspend()
                freshFile.delete()
                tempDir.toFile().deleteRecursively()
            }
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `stops deleting after shutdown`() {
        val tempDir = Files.createTempDirectory("image-deleter-stop-test")
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val retentionMillis = 100L
            val nowMs = 1_000L
            val deleter =
                ImageDeleter(
                    tempDir.toString(),
                    deletionInterval = 50L,
                    retentionMillis = retentionMillis,
                    dispatcher = dispatcher,
                    clock = { nowMs },
                )

            try {
                deleter.startDeletion()
                advanceTimeBy(150L)
                runCurrent()
                deleter.stopDeletionSuspend()
                advanceUntilIdle()

                val fileAfterStop = tempDir.resolve("after-stop.jpg").apply { writeText("after") }.toFile()
                fileAfterStop.setLastModified(0L)
                advanceTimeBy(250L)
                runCurrent()

                assertTrue(fileAfterStop.exists())
                assertEquals(1, tempDir.toFile().listFiles()?.size)
            } finally {
                tempDir.toFile().deleteRecursively()
            }
        }
    }
}
