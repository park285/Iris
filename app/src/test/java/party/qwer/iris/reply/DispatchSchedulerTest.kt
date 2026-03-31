package party.qwer.iris.reply

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DispatchSchedulerTest {
    @Test
    fun `paces sequential send invocations`() =
        runTest {
            val scheduler =
                DispatchScheduler(
                    baseIntervalMs = { 50L },
                    jitterMaxMs = { 0L },
                    clock = { testScheduler.currentTime },
                )

            val timestamps = mutableListOf<Long>()

            repeat(3) {
                scheduler.awaitPermit()
                timestamps.add(testScheduler.currentTime)
            }

            assertEquals(3, timestamps.size)
            for (i in 1 until timestamps.size) {
                val gap = timestamps[i] - timestamps[i - 1]
                assertTrue(gap >= 50, "sends should be paced by at least 50ms (gap was ${gap}ms)")
            }
        }

    @Test
    fun `zero interval does not block`() =
        runTest {
            val scheduler =
                DispatchScheduler(
                    baseIntervalMs = { 0L },
                    jitterMaxMs = { 0L },
                    clock = { testScheduler.currentTime },
                )

            repeat(5) {
                scheduler.awaitPermit()
            }

            assertEquals(0L, testScheduler.currentTime)
        }

    @Test
    fun `jitter adds variance to pacing`() =
        runTest {
            val scheduler =
                DispatchScheduler(
                    baseIntervalMs = { 10L },
                    jitterMaxMs = { 50L },
                    clock = { testScheduler.currentTime },
                )

            val timestamps = mutableListOf<Long>()

            repeat(5) {
                scheduler.awaitPermit()
                timestamps.add(testScheduler.currentTime)
            }

            assertEquals(5, timestamps.size)
            for (i in 1 until timestamps.size) {
                val gap = timestamps[i] - timestamps[i - 1]
                assertTrue(gap >= 10, "gap should be at least base interval (gap was ${gap}ms)")
            }
        }
}
