package party.qwer.iris

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ThreadLocalRandom

internal class GlobalDispatchGate(
    private val baseIntervalMs: () -> Long,
    private val jitterMaxMs: () -> Long,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()

    @Volatile
    private var nextAllowedAtMs: Long = 0L

    suspend fun awaitPermit() {
        val waitMs =
            mutex.withLock {
                val now = clock()
                val scheduledAt = maxOf(now, nextAllowedAtMs)

                val jitterMax = jitterMaxMs()
                val jitterMs =
                    if (jitterMax > 0) {
                        ThreadLocalRandom.current().nextLong(jitterMax + 1)
                    } else {
                        0L
                    }
                val baseMs = baseIntervalMs()
                nextAllowedAtMs = scheduledAt + baseMs + jitterMs
                IrisLogger.debugLazy {
                    "[DispatchGate] next slot in ${baseMs + jitterMs}ms (base=$baseMs jitter=$jitterMs)"
                }
                (scheduledAt - now).coerceAtLeast(0L)
            }

        if (waitMs > 0) {
            IrisLogger.debugLazy { "[DispatchGate] pacing wait ${waitMs}ms" }
            delay(waitMs)
        }
    }
}
