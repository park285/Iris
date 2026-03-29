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
    private val fullReconcileIntervalMs: Long = 60_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var job: Job? = null

    @Volatile
    private var nextFullReconcileAtMs: Long = 0L

    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        nextFullReconcileAtMs = clock() + fullReconcileIntervalMs
        job =
            coroutineScope.launch {
                while (isActive) {
                    try {
                        if (clock() >= nextFullReconcileAtMs) {
                            observerHelper.markAllRoomsDirty()
                            nextFullReconcileAtMs = clock() + fullReconcileIntervalMs
                        }
                        val backlog = observerHelper.dirtyRoomCount()
                        val drainBudget =
                            when {
                                backlog >= maxRoomsPerTick * 8 -> maxRoomsPerTick * 4
                                backlog >= maxRoomsPerTick * 4 -> maxRoomsPerTick * 2
                                else -> maxRoomsPerTick
                            }
                        observerHelper.runDirtySnapshotDiff(drainBudget)
                    } catch (e: Exception) {
                        IrisLogger.error("[SnapshotObserver] error: ${e.message}", e)
                    }
                    delay(intervalMs)
                }
            }
        IrisLogger.info(
            "[SnapshotObserver] started (intervalMs=$intervalMs, maxRoomsPerTick=$maxRoomsPerTick, fullReconcileIntervalMs=$fullReconcileIntervalMs)",
        )
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
