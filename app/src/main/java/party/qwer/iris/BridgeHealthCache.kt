package party.qwer.iris

import party.qwer.iris.model.ImageBridgeHealthResult
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal class BridgeHealthCache(
    private val healthProvider: () -> ImageBridgeHealthResult,
    private val refreshIntervalMs: Long = 5_000L,
    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "iris-bridge-health-cache").apply {
                isDaemon = true
            }
        },
) {
    private val currentHealth = AtomicReference<ImageBridgeHealthResult?>()

    @Volatile
    private var started = false

    @Synchronized
    fun start() {
        if (started) {
            return
        }
        started = true
        refreshNow()
        executor.scheduleWithFixedDelay(
            { refreshNow() },
            refreshIntervalMs,
            refreshIntervalMs,
            TimeUnit.MILLISECONDS,
        )
    }

    fun refreshNow() {
        currentHealth.set(healthProvider())
    }

    fun current(): ImageBridgeHealthResult? = currentHealth.get()

    @Synchronized
    fun stop() {
        if (!started) {
            return
        }
        started = false
        executor.shutdownNow()
    }
}
