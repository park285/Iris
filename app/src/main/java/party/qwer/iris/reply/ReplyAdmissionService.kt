package party.qwer.iris.reply

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import party.qwer.iris.IrisLogger
import party.qwer.iris.ReplyAdmissionResult
import party.qwer.iris.ReplyAdmissionStatus
import party.qwer.iris.ReplyQueueKey

internal interface PipelineRequest {
    val requestId: String?

    suspend fun prepare() {}

    suspend fun send()
}

internal enum class ReplyAdmissionLifecycle {
    STOPPED,
    RUNNING,
    TERMINATED,
}

internal data class ReplyAdmissionDebugSnapshot(
    val lifecycle: ReplyAdmissionLifecycle,
    val activeWorkers: Int,
)

internal class ReplyAdmissionService(
    private val maxWorkers: Int = 16,
    private val perWorkerQueueCapacity: Int = 16,
    private val workerIdleTimeoutMs: Long = 60_000L,
    private val shutdownTimeoutMs: Long = 10_000L,
    internal var onRequestProcess: (suspend (PipelineRequest) -> Unit)? = null,
) {
    private data class WorkerState(
        val workerId: Long,
        val key: ReplyQueueKey,
        val channel: Channel<PipelineRequest>,
        val job: Job,
    )

    private sealed interface AdmissionCommand {
        data class Start(
            val reply: CompletableDeferred<Boolean>,
        ) : AdmissionCommand

        data class Restart(
            val reply: CompletableDeferred<WorkerClosePlan>,
        ) : AdmissionCommand

        data class RestartCompleted(
            val reply: CompletableDeferred<Unit>,
        ) : AdmissionCommand

        data class Shutdown(
            val reply: CompletableDeferred<WorkerClosePlan>,
        ) : AdmissionCommand

        data class Enqueue(
            val key: ReplyQueueKey,
            val request: PipelineRequest,
            val reply: CompletableDeferred<ReplyAdmissionResult>,
        ) : AdmissionCommand

        data class DebugSnapshot(
            val reply: CompletableDeferred<ReplyAdmissionDebugSnapshot>,
        ) : AdmissionCommand

        data class WorkerClosed(
            val key: ReplyQueueKey,
            val workerId: Long,
            val idleTimeout: Boolean,
        ) : AdmissionCommand
    }

    private data class WorkerClosePlan(
        val allowed: Boolean,
        val workers: List<WorkerState> = emptyList(),
        val workerScope: CoroutineScope? = null,
    )

    private val commands = Channel<AdmissionCommand>(Channel.UNLIMITED)
    private val commandScope = buildCoroutineScope()

    private var workerRegistry = mutableMapOf<ReplyQueueKey, WorkerState>()
    private var workerScope = buildCoroutineScope()
    private var lifecycle = ReplyAdmissionLifecycle.STOPPED
    private var nextWorkerId = 0L

    init {
        commandScope.launch {
            for (command in commands) {
                handleCommand(command)
            }
        }
    }

    fun start() {
        val started = dispatchCommand<AdmissionCommand.Start, Boolean> { AdmissionCommand.Start(it) }
        if (started) {
            IrisLogger.debug("[ReplyAdmissionService] started")
        }
    }

    fun restart() {
        IrisLogger.info("[ReplyAdmissionService] Restarting...")
        val plan =
            dispatchCommand<AdmissionCommand.Restart, WorkerClosePlan> {
                AdmissionCommand.Restart(it)
            }
        if (!plan.allowed) {
            return
        }
        closeWorkers(plan.workers)
        plan.workerScope?.cancel()
        dispatchCommand<AdmissionCommand.RestartCompleted, Unit> {
            AdmissionCommand.RestartCompleted(it)
        }
        IrisLogger.info("[ReplyAdmissionService] Restart complete")
    }

    fun shutdown() {
        IrisLogger.info("[ReplyAdmissionService] Shutting down...")
        val plan =
            dispatchCommand<AdmissionCommand.Shutdown, WorkerClosePlan> {
                AdmissionCommand.Shutdown(it)
            }
        closeWorkers(plan.workers)
        plan.workerScope?.cancel()
        IrisLogger.info("[ReplyAdmissionService] Shutdown complete")
    }

    fun enqueue(
        key: ReplyQueueKey,
        request: PipelineRequest,
    ): ReplyAdmissionResult =
        dispatchCommand<AdmissionCommand.Enqueue, ReplyAdmissionResult> {
            AdmissionCommand.Enqueue(key, request, it)
        }

    internal fun debugSnapshot(): ReplyAdmissionDebugSnapshot =
        dispatchCommand<AdmissionCommand.DebugSnapshot, ReplyAdmissionDebugSnapshot> {
            AdmissionCommand.DebugSnapshot(it)
        }

    private fun handleCommand(command: AdmissionCommand) {
        when (command) {
            is AdmissionCommand.Start -> handleStart(command)
            is AdmissionCommand.Restart -> handleRestart(command)
            is AdmissionCommand.RestartCompleted -> handleRestartCompleted(command)
            is AdmissionCommand.Shutdown -> handleShutdown(command)
            is AdmissionCommand.Enqueue -> handleEnqueue(command)
            is AdmissionCommand.DebugSnapshot -> handleDebugSnapshot(command)
            is AdmissionCommand.WorkerClosed -> handleWorkerClosed(command)
        }
    }

    private fun handleStart(command: AdmissionCommand.Start) {
        if (lifecycle == ReplyAdmissionLifecycle.TERMINATED) {
            IrisLogger.error("[ReplyAdmissionService] Cannot start after shutdown")
            command.reply.complete(false)
        } else {
            lifecycle = ReplyAdmissionLifecycle.RUNNING
            command.reply.complete(true)
        }
    }

    private fun handleRestart(command: AdmissionCommand.Restart) {
        if (lifecycle == ReplyAdmissionLifecycle.TERMINATED) {
            IrisLogger.error("[ReplyAdmissionService] Cannot restart after shutdown")
            command.reply.complete(WorkerClosePlan(allowed = false))
            return
        }

        val workers = workerRegistry.values.toList()
        val oldWorkerScope = workerScope
        workerRegistry.clear()
        lifecycle = ReplyAdmissionLifecycle.STOPPED
        workerScope = buildCoroutineScope()
        command.reply.complete(
            WorkerClosePlan(
                allowed = true,
                workers = workers,
                workerScope = oldWorkerScope,
            ),
        )
    }

    private fun handleRestartCompleted(command: AdmissionCommand.RestartCompleted) {
        if (lifecycle != ReplyAdmissionLifecycle.TERMINATED) {
            lifecycle = ReplyAdmissionLifecycle.RUNNING
        }
        command.reply.complete(Unit)
    }

    private fun handleShutdown(command: AdmissionCommand.Shutdown) {
        val workers = workerRegistry.values.toList()
        val oldWorkerScope = workerScope
        workerRegistry.clear()
        lifecycle = ReplyAdmissionLifecycle.TERMINATED
        command.reply.complete(
            WorkerClosePlan(
                allowed = true,
                workers = workers,
                workerScope = oldWorkerScope,
            ),
        )
    }

    private fun handleEnqueue(command: AdmissionCommand.Enqueue) {
        val result =
            if (lifecycle != ReplyAdmissionLifecycle.RUNNING) {
                ReplyAdmissionResult(ReplyAdmissionStatus.SHUTDOWN, "reply sender unavailable")
            } else {
                val worker = getOrCreateWorkerLocked(command.key)
                if (worker == null) {
                    ReplyAdmissionResult(
                        ReplyAdmissionStatus.QUEUE_FULL,
                        "too many active reply workers",
                    )
                } else {
                    enqueueToWorker(command.key, worker, command.request)
                }
            }
        command.reply.complete(result)
    }

    private fun handleDebugSnapshot(command: AdmissionCommand.DebugSnapshot) {
        command.reply.complete(
            ReplyAdmissionDebugSnapshot(
                lifecycle = lifecycle,
                activeWorkers = workerRegistry.size,
            ),
        )
    }

    private fun handleWorkerClosed(command: AdmissionCommand.WorkerClosed) {
        val current = workerRegistry[command.key]
        if (current?.workerId == command.workerId) {
            workerRegistry.remove(command.key)
        }
    }

    private fun getOrCreateWorkerLocked(key: ReplyQueueKey): WorkerState? {
        workerRegistry[key]?.let { return it }
        if (workerRegistry.size >= maxWorkers) {
            return null
        }
        return launchWorkerLocked(key).also { workerRegistry[key] = it }
    }

    private fun launchWorkerLocked(key: ReplyQueueKey): WorkerState {
        val channel = Channel<PipelineRequest>(perWorkerQueueCapacity)
        val workerId = nextWorkerId++
        lateinit var state: WorkerState
        val job =
            workerScope.launch {
                var idleTimeout = false
                try {
                    while (true) {
                        val receiveResult =
                            withTimeoutOrNull(workerIdleTimeoutMs) {
                                channel.receiveCatching()
                            }
                        if (receiveResult == null) {
                            idleTimeout = true
                            break
                        }
                        val request = receiveResult.getOrNull() ?: break
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
                    val reason = if (idleTimeout) "idle timeout" else "channel closed"
                    commands.trySend(
                        AdmissionCommand.WorkerClosed(
                            key = key,
                            workerId = workerId,
                            idleTimeout = idleTimeout,
                        ),
                    )
                    IrisLogger.debug("[ReplyAdmissionService] worker($key) terminated ($reason)")
                }
            }
        state = WorkerState(workerId = workerId, key = key, channel = channel, job = job)
        return state
    }

    private fun enqueueToWorker(
        key: ReplyQueueKey,
        worker: WorkerState,
        request: PipelineRequest,
    ): ReplyAdmissionResult {
        val sendResult = worker.channel.trySend(request)
        return when {
            sendResult.isSuccess -> {
                IrisLogger.debug("[ReplyAdmissionService] Request queued to worker($key)")
                ReplyAdmissionResult(ReplyAdmissionStatus.ACCEPTED)
            }
            sendResult.isClosed -> {
                val current = workerRegistry[key]
                if (current?.workerId == worker.workerId) {
                    workerRegistry.remove(key)
                }
                if (lifecycle != ReplyAdmissionLifecycle.RUNNING) {
                    ReplyAdmissionResult(ReplyAdmissionStatus.SHUTDOWN, "reply sender unavailable")
                } else {
                    val retryWorker =
                        getOrCreateWorkerLocked(key)
                            ?: return ReplyAdmissionResult(
                                ReplyAdmissionStatus.QUEUE_FULL,
                                "too many active reply workers",
                            )
                    enqueueToWorker(key, retryWorker, request)
                }
            }
            else -> {
                IrisLogger.error("[ReplyAdmissionService] Queue full for worker($key)")
                ReplyAdmissionResult(ReplyAdmissionStatus.QUEUE_FULL, "reply queue is full")
            }
        }
    }

    private fun closeWorkers(workers: List<WorkerState>) {
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
    }

    private fun <C : AdmissionCommand, T> dispatchCommand(build: (CompletableDeferred<T>) -> C): T =
        runBlocking {
            val reply = CompletableDeferred<T>()
            commands.send(build(reply))
            reply.await()
        }

    private fun buildCoroutineScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
