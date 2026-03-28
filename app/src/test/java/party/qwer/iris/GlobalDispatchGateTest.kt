package party.qwer.iris

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertTrue

class GlobalDispatchGateTest {
    @Test
    fun `awaitPermit only paces start time and does not serialize execution`() {
        val gate = GlobalDispatchGate(baseIntervalMs = { 0L }, jitterMaxMs = { 0L })
        val concurrent = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)
        val latch = CountDownLatch(4)

        repeat(4) {
            Thread {
                runBlocking {
                    gate.awaitPermit()
                    val cur = concurrent.incrementAndGet()
                    maxConcurrent.updateAndGet { prev -> maxOf(prev, cur) }
                    delay(20)
                    concurrent.decrementAndGet()
                }
                latch.countDown()
            }.start()
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS), "all permit waits should complete")
        assertTrue(maxConcurrent.get() > 1, "send execution should be allowed to overlap after permit")
    }

    @Test
    fun `awaitPermit enforces minimum interval between send starts`() {
        val gate = GlobalDispatchGate(baseIntervalMs = { 100L }, jitterMaxMs = { 0L })
        val timestamps = CopyOnWriteArrayList<Long>()

        runBlocking {
            repeat(3) {
                gate.awaitPermit()
                timestamps.add(System.currentTimeMillis())
            }
        }

        assertTrue(timestamps.size == 3)
        for (i in 1 until timestamps.size) {
            val gap = timestamps[i] - timestamps[i - 1]
            assertTrue(gap >= 90, "gap between send starts should be >= 100ms (was ${gap}ms)")
        }
    }

    @Test
    fun `awaitPermit adds bounded positive jitter to interval`() {
        val gate = GlobalDispatchGate(baseIntervalMs = { 100L }, jitterMaxMs = { 50L })
        val timestamps = CopyOnWriteArrayList<Long>()

        runBlocking {
            repeat(6) {
                gate.awaitPermit()
                timestamps.add(System.currentTimeMillis())
            }
        }

        val intervals = (1 until timestamps.size).map { i -> timestamps[i] - timestamps[i - 1] }
        intervals.forEach { gap ->
            assertTrue(gap >= 90, "interval should be >= baseInterval (was ${gap}ms)")
            assertTrue(gap <= 200, "interval should be <= base + jitter + tolerance (was ${gap}ms)")
        }
    }

    @Test
    fun `zero jitter produces consistent intervals`() {
        val gate = GlobalDispatchGate(baseIntervalMs = { 80L }, jitterMaxMs = { 0L })
        val timestamps = CopyOnWriteArrayList<Long>()

        runBlocking {
            repeat(4) {
                gate.awaitPermit()
                timestamps.add(System.currentTimeMillis())
            }
        }

        val intervals = (1 until timestamps.size).map { i -> timestamps[i] - timestamps[i - 1] }
        intervals.forEach { gap ->
            assertTrue(gap in 70..120, "interval should be close to 80ms without jitter (was ${gap}ms)")
        }
    }

    @Test
    fun `failed send still consumes pacing slot after awaitPermit`() {
        val gate = GlobalDispatchGate(baseIntervalMs = { 200L }, jitterMaxMs = { 0L })
        val start = System.currentTimeMillis()

        runBlocking {
            runCatching {
                gate.awaitPermit()
                error("fail")
            }
            gate.awaitPermit()
        }
        val elapsed = System.currentTimeMillis() - start

        assertTrue(elapsed >= 150, "second permit should still observe pacing after caller failure (took ${elapsed}ms)")
    }
}
