package party.qwer.iris.reply

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import party.qwer.iris.IrisLogger
import party.qwer.iris.ReplyAdmissionResult
import party.qwer.iris.ReplyAdmissionStatus
import party.qwer.iris.ReplyQueueKey
import java.util.concurrent.ConcurrentHashMap

internal interface PipelineRequest {
    val requestId: String?

    suspend fun prepare() {}

    suspend fun send()
}

internal class ReplyAdmissionService(
    private val maxWorkers: Int = 16,
    private val perWorkerQueueCapacity: Int = 16,
    private val workerIdleTimeoutMs: Long = 60_000L,
    private val shutdownTimeoutMs: Long = 10_000L,
    internal var onRequestProcess: (suspend (PipelineRequest) -> Unit)? = null,
) {
    private data class WorkerState(
        val key: ReplyQueueKey,
        val channel: Channel<PipelineRequest>,
        val job: Job,
    )

    private val workerRegistry = ConcurrentHashMap<ReplyQueueKey, WorkerState>()
    private var coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false
    private var shutdownComplete = false

    @Synchronized
    fun start() {
        if (shutdownComplete) {
            IrisLogger.error("[ReplyAdmissionService] Cannot start after shutdown")
            return
        }
        started = true
        IrisLogger.debug("[ReplyAdmissionService] started")
    }

    fun restart() {
        IrisLogger.info("[ReplyAdmissionService] Restarting...")
        val snapshot: List<WorkerState>
        synchronized(this) {
            if (shutdownComplete) {
                IrisLogger.error("[ReplyAdmissionService] Cannot restart after shutdown")
                return
            }
            started = false
            snapshot = workerRegistry.values.toList()
        }
        snapshot.forEach { it.channel.close() }
        runBlocking {
            snapshot.forEach { worker ->
                withTimeoutOrNull(shutdownTimeoutMs) { worker.job.join() } ?: run {
                    worker.job.cancel()
                    withTimeoutOrNull(shutdownTimeoutMs) { worker.job.join() }
                        ?: IrisLogger.error("[ReplyAdmissionService] Worker(${worker.key}) cancel timed out; abandoning")
                }
            }
        }
        synchronized(this) {
            workerRegistry.clear()
            started = true
        }
        IrisLogger.info("[ReplyAdmissionService] Restart complete")
    }

    fun shutdown() {
        IrisLogger.info("[ReplyAdmissionService] Shutting down...")
        synchronized(this) {
            started = false
            shutdownComplete = true
        }
        val workers = workerRegistry.values.toList()
        workers.forEach { it.channel.close() }
        runBlocking {
            workers.forEach { worker ->
                withTimeoutOrNull(shutdownTimeoutMs) { worker.job.join() } ?: run {
                    worker.job.cancel()
                    withTimeoutOrNull(shutdownTimeoutMs) { worker.job.join() }
                        ?: IrisLogger.error("[ReplyAdmissionService] Worker(${worker.key}) cancel timed out; abandoning")
                }
            }
        }
        workerRegistry.clear()
        IrisLogger.info("[ReplyAdmissionService] Shutdown complete")
    }

    @Synchronized
    fun enqueue(
        key: ReplyQueueKey,
        request: PipelineRequest,
    ): ReplyAdmissionResult {
        if (!started) {
            return ReplyAdmissionResult(ReplyAdmissionStatus.SHUTDOWN, "reply sender unavailable")
        }

        val worker =
            getOrCreateWorker(key)
                ?: return ReplyAdmissionResult(ReplyAdmissionStatus.QUEUE_FULL, "too many active reply workers")

        val sendResult = worker.channel.trySend(request)
        return when {
            sendResult.isSuccess -> {
                IrisLogger.debug("[ReplyAdmissionService] Request queued to worker($key)")
                ReplyAdmissionResult(ReplyAdmissionStatus.ACCEPTED)
            }
            sendResult.isClosed -> {
                workerRegistry.remove(key, worker)
                val retryWorker =
                    getOrCreateWorker(key)
                        ?: return ReplyAdmissionResult(ReplyAdmissionStatus.QUEUE_FULL, "too many active reply workers")
                val retryResult = retryWorker.channel.trySend(request)
                if (retryResult.isSuccess) {
                    ReplyAdmissionResult(ReplyAdmissionStatus.ACCEPTED)
                } else {
                    ReplyAdmissionResult(ReplyAdmissionStatus.QUEUE_FULL, "reply queue is full")
                }
            }
            else -> {
                IrisLogger.error("[ReplyAdmissionService] Queue full for worker($key)")
                ReplyAdmissionResult(ReplyAdmissionStatus.QUEUE_FULL, "reply queue is full")
            }
        }
    }

    private fun getOrCreateWorker(key: ReplyQueueKey): WorkerState? {
        workerRegistry[key]?.let { return it }

        synchronized(this) {
            workerRegistry[key]?.let { return it }
            if (workerRegistry.size >= maxWorkers) {
                return null
            }
            val worker = launchWorker(key)
            workerRegistry[key] = worker
            return worker
        }
    }

    private fun launchWorker(key: ReplyQueueKey): WorkerState {
        val channel = Channel<PipelineRequest>(perWorkerQueueCapacity)
        lateinit var state: WorkerState
        val job =
            coroutineScope.launch {
                var idleTimeout = false
                try {
                    while (true) {
                        val request =
                            withTimeoutOrNull(workerIdleTimeoutMs) {
                                channel.receive()
                            }
                        if (request == null) {
                            idleTimeout = true
                            break
                        }
                        try {
                            onRequestProcess?.invoke(request) ?: run {
                                request.prepare()
                                request.send()
                            }
                        } catch (e: Exception) {
                            IrisLogger.error("[ReplyAdmissionService] worker($key) error: ${e.message}", e)
                        }
                    }
                } finally {
                    channel.close()
                    workerRegistry.remove(key, state)
                    val reason = if (idleTimeout) "idle timeout" else "channel closed"
                    IrisLogger.debug("[ReplyAdmissionService] worker($key) terminated ($reason)")
                }
            }
        state = WorkerState(key, channel, job)
        return state
    }
}
