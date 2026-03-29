package party.qwer.iris.reply

import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertTrue

class DispatchSchedulerTest {
    @Test
    fun `paces sequential send invocations`() {
        val scheduler =
            DispatchScheduler(
                baseIntervalMs = { 50L },
                jitterMaxMs = { 0L },
            )

        val timestamps = CopyOnWriteArrayList<Long>()

        runBlocking {
            repeat(3) {
                scheduler.awaitPermit()
                timestamps.add(System.currentTimeMillis())
            }
        }

        assertTrue(timestamps.size == 3)
        for (i in 1 until timestamps.size) {
            val gap = timestamps[i] - timestamps[i - 1]
            assertTrue(gap >= 30, "sends should be paced by at least ~50ms (gap was ${gap}ms)")
        }
    }

    @Test
    fun `zero interval does not block`() {
        val scheduler =
            DispatchScheduler(
                baseIntervalMs = { 0L },
                jitterMaxMs = { 0L },
            )

        val start = System.currentTimeMillis()
        runBlocking {
            repeat(5) {
                scheduler.awaitPermit()
            }
        }
        val elapsed = System.currentTimeMillis() - start

        assertTrue(elapsed < 500, "zero interval should not block significantly (took ${elapsed}ms)")
    }

    @Test
    fun `jitter adds variance to pacing`() {
        val scheduler =
            DispatchScheduler(
                baseIntervalMs = { 10L },
                jitterMaxMs = { 50L },
            )

        val timestamps = CopyOnWriteArrayList<Long>()

        runBlocking {
            repeat(5) {
                scheduler.awaitPermit()
                timestamps.add(System.currentTimeMillis())
            }
        }

        assertTrue(timestamps.size == 5)
        for (i in 1 until timestamps.size) {
            val gap = timestamps[i] - timestamps[i - 1]
            assertTrue(gap >= 5, "gap should be at least near base interval (gap was ${gap}ms)")
        }
    }
}
