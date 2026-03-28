package party.qwer.iris

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GlobalDispatchGateTest {
    @Test
    fun `dispatch serializes concurrent calls`() {
        val gate = GlobalDispatchGate(baseIntervalMs = { 0L }, jitterMaxMs = { 0L })
        val concurrent = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)
        val latch = CountDownLatch(4)

        repeat(4) {
            Thread {
                runBlocking {
                    gate.dispatch {
                        val cur = concurrent.incrementAndGet()
                        maxConcurrent.updateAndGet { prev -> maxOf(prev, cur) }
                        delay(20)
                        concurrent.decrementAndGet()
                    }
                }
                latch.countDown()
            }.start()
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS), "all dispatches should complete")
        assertEquals(1, maxConcurrent.get(), "dispatch calls must not overlap")
    }

    @Test
    fun `dispatch enforces minimum interval between sends`() {
        val gate = GlobalDispatchGate(baseIntervalMs = { 100L }, jitterMaxMs = { 0L })
        val timestamps = CopyOnWriteArrayList<Long>()

        runBlocking {
            repeat(3) {
                gate.dispatch {
                    timestamps.add(System.currentTimeMillis())
                }
            }
        }

        assertEquals(3, timestamps.size)
        for (i in 1 until timestamps.size) {
            val gap = timestamps[i] - timestamps[i - 1]
            assertTrue(gap >= 90, "gap between sends should be >= 100ms (was ${gap}ms)")
        }
    }

    @Test
    fun `dispatch adds bounded positive jitter to interval`() {
        val gate = GlobalDispatchGate(baseIntervalMs = { 100L }, jitterMaxMs = { 50L })
        val timestamps = CopyOnWriteArrayList<Long>()

        runBlocking {
            repeat(6) {
                gate.dispatch {
                    timestamps.add(System.currentTimeMillis())
                }
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
                gate.dispatch {
                    timestamps.add(System.currentTimeMillis())
                }
            }
        }

        val intervals = (1 until timestamps.size).map { i -> timestamps[i] - timestamps[i - 1] }
        intervals.forEach { gap ->
            assertTrue(gap in 70..120, "interval should be close to 80ms without jitter (was ${gap}ms)")
        }
    }

    @Test
    fun `dispatch recovers from block exception`() {
        val gate = GlobalDispatchGate(baseIntervalMs = { 0L }, jitterMaxMs = { 0L })

        runBlocking {
            assertFailsWith<RuntimeException> {
                gate.dispatch { throw RuntimeException("boom") }
            }

            val result = gate.dispatch { "recovered" }
            assertEquals("recovered", result)
        }
    }

    @Test
    fun `dispatch returns block result`() {
        val gate = GlobalDispatchGate(baseIntervalMs = { 0L }, jitterMaxMs = { 0L })

        val result = runBlocking { gate.dispatch { 42 } }
        assertEquals(42, result)
    }

    @Test
    fun `failed dispatch does not consume pacing slot`() {
        val gate = GlobalDispatchGate(baseIntervalMs = { 200L }, jitterMaxMs = { 0L })

        val start = System.currentTimeMillis()
        runBlocking {
            runCatching { gate.dispatch { error("fail") } }
            gate.dispatch { }
        }
        val elapsed = System.currentTimeMillis() - start

        assertTrue(elapsed < 150, "second dispatch should not wait for pacing after failure (took ${elapsed}ms)")
    }
}
