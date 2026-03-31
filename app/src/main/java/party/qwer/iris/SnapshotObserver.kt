package party.qwer.iris

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import party.qwer.iris.persistence.CheckpointJournal
import party.qwer.iris.snapshot.SnapshotCommand
import party.qwer.iris.snapshot.SnapshotCoordinator

internal class SnapshotObserver(
    private val snapshotCoordinator: SnapshotCoordinator,
    private val checkpointJournal: CheckpointJournal,
    private val intervalMs: Long = 5_000L,
    private val maxRoomsPerTick: Int = 32,
    private val fullReconcileIntervalMs: Long = 60_000L,
    private val missingTombstoneTtlMs: Long? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val coroutineScope = CoroutineScope(SupervisorJob() + dispatcher)

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
                            snapshotCoordinator.send(SnapshotCommand.FullReconcile)
                            nextFullReconcileAtMs = clock() + fullReconcileIntervalMs
                        }
                        val backlog = snapshotCoordinator.debugSnapshotSuspend().dirtyRoomCount
                        val drainBudget =
                            when {
                                backlog >= maxRoomsPerTick * 8 -> maxRoomsPerTick * 4
                                backlog >= maxRoomsPerTick * 4 -> maxRoomsPerTick * 2
                                else -> maxRoomsPerTick
                            }
                        snapshotCoordinator.send(SnapshotCommand.Drain(drainBudget))
                        missingTombstoneTtlMs?.let { ttlMs ->
                            val cutoffEpochMs = (clock() - ttlMs).coerceAtLeast(0L)
                            snapshotCoordinator.send(SnapshotCommand.PruneMissing(cutoffEpochMs))
                        }
                        checkpointJournal.flushIfDirty()
                    } catch (e: Exception) {
                        IrisLogger.error("[SnapshotObserver] error: ${e.message}", e)
                    }
                    delay(intervalMs)
                }
            }
        IrisLogger.info(
            "[SnapshotObserver] started (intervalMs=$intervalMs, maxRoomsPerTick=$maxRoomsPerTick, " +
                "fullReconcileIntervalMs=$fullReconcileIntervalMs)",
        )
    }

    fun stop() {
        runBlocking { stopSuspend() }
    }

    suspend fun stopSuspend() {
        val captured =
            synchronized(this) {
                val current = job ?: return
                job = null
                current
            }
        captured.cancelAndJoin()
        IrisLogger.info("[SnapshotObserver] stopped")
    }
}
