package party.qwer.iris

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

internal class SnapshotObserver(
    private val observerHelper: ObserverHelper,
    private val intervalMs: Long = 5_000L,
    private val maxRoomsPerTick: Int = 32,
) {
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var job: Job? = null

    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        job = coroutineScope.launch {
            while (isActive) {
                try {
                    observerHelper.runDirtySnapshotDiff(maxRoomsPerTick)
                } catch (e: Exception) {
                    IrisLogger.error("[SnapshotObserver] error: ${e.message}", e)
                }
                delay(intervalMs)
            }
        }
        IrisLogger.info("[SnapshotObserver] started (intervalMs=$intervalMs, maxRoomsPerTick=$maxRoomsPerTick)")
    }

    fun stop() {
        val captured =
            synchronized(this) {
                val current = job ?: return
                job = null
                current
            }
        runBlocking { captured.cancelAndJoin() }
        IrisLogger.info("[SnapshotObserver] stopped")
    }
}
