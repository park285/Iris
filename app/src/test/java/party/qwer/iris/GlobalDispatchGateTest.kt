package party.qwer.iris

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class GlobalDispatchGateTest {
    @Test
    fun `awaitPermit only paces start time and does not serialize execution`() = runTest {
        val gate = GlobalDispatchGate(baseIntervalMs = { 0L }, jitterMaxMs = { 0L }, clock = { testScheduler.currentTime })
        val concurrent = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)
        val completed = AtomicInteger(0)

        repeat(4) {
            launch(StandardTestDispatcher(testScheduler)) {
                gate.awaitPermit()
                val cur = concurrent.incrementAndGet()
                maxConcurrent.updateAndGet { prev -> maxOf(prev, cur) }
                kotlinx.coroutines.delay(20)
                concurrent.decrementAndGet()
                completed.incrementAndGet()
            }
        }

        advanceUntilIdle()
        assertEquals(4, completed.get(), "all permit waits should complete")
        assertTrue(maxConcurrent.get() > 1, "send execution should be allowed to overlap after permit")
    }

    @Test
    fun `awaitPermit enforces minimum interval between send starts`() = runTest {
        val gate = GlobalDispatchGate(baseIntervalMs = { 100L }, jitterMaxMs = { 0L }, clock = { testScheduler.currentTime })
        val timestamps = mutableListOf<Long>()

        repeat(3) {
            gate.awaitPermit()
            timestamps.add(testScheduler.currentTime)
        }

        assertEquals(3, timestamps.size)
        for (i in 1 until timestamps.size) {
            val gap = timestamps[i] - timestamps[i - 1]
            assertEquals(100L, gap, "gap between send starts should be 100ms")
        }
    }

    @Test
    fun `awaitPermit adds bounded positive jitter to interval`() = runTest {
        val gate = GlobalDispatchGate(baseIntervalMs = { 100L }, jitterMaxMs = { 50L }, clock = { testScheduler.currentTime })
        val timestamps = mutableListOf<Long>()

        repeat(6) {
            gate.awaitPermit()
            timestamps.add(testScheduler.currentTime)
        }

        val intervals = (1 until timestamps.size).map { i -> timestamps[i] - timestamps[i - 1] }
        intervals.forEach { gap ->
            assertTrue(gap >= 100, "interval should be >= baseInterval (was ${gap}ms)")
            assertTrue(gap <= 150, "interval should be <= base + jitter (was ${gap}ms)")
        }
    }

    @Test
    fun `zero jitter produces consistent intervals`() = runTest {
        val gate = GlobalDispatchGate(baseIntervalMs = { 80L }, jitterMaxMs = { 0L }, clock = { testScheduler.currentTime })
        val timestamps = mutableListOf<Long>()

        repeat(4) {
            gate.awaitPermit()
            timestamps.add(testScheduler.currentTime)
        }

        val intervals = (1 until timestamps.size).map { i -> timestamps[i] - timestamps[i - 1] }
        intervals.forEach { gap ->
            assertEquals(80L, gap, "interval should be exactly 80ms without jitter")
        }
    }

    @Test
    fun `failed send still consumes pacing slot after awaitPermit`() = runTest {
        val gate = GlobalDispatchGate(baseIntervalMs = { 200L }, jitterMaxMs = { 0L }, clock = { testScheduler.currentTime })

        runCatching {
            gate.awaitPermit()
            error("fail")
        }
        val beforeSecond = testScheduler.currentTime
        gate.awaitPermit()
        val elapsed = testScheduler.currentTime - beforeSecond

        assertEquals(200L, elapsed, "second permit should observe pacing after caller failure")
    }
}
