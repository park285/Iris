package party.qwer.iris.reply

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import party.qwer.iris.IrisLogger
import party.qwer.iris.ReplyAdmissionResult
import party.qwer.iris.ReplyAdmissionStatus
import party.qwer.iris.ReplyQueueKey

internal interface PipelineRequest {
    val requestId: String?

    suspend fun prepare() {}

    suspend fun discard() {}

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
    val closingWorkers: Int,
    val workers: List<ReplyAdmissionWorkerDebug>,
)

internal data class ReplyAdmissionWorkerDebug(
    val key: ReplyQueueKey,
    val workerId: Long,
    val ageMs: Long,
    val queueDepth: Int,
    val mailboxState: String,
)

internal class ReplyAdmissionService(
    private val maxWorkers: Int = 16,
    private val perWorkerQueueCapacity: Int = 16,
    private val workerIdleTimeoutMs: Long = 60_000L,
    private val shutdownTimeoutMs: Long = 10_000L,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val commandChannelCapacity: Int = Channel.BUFFERED,
    private val clock: () -> Long = System::currentTimeMillis,
    initialRequestProcessor: suspend (PipelineRequest) -> Unit = { request ->
        request.prepare()
        request.send()
    },
) {
    private sealed interface WorkerMailboxState {
        data class Open(
            val channel: Channel<PipelineRequest>,
            val job: Job,
            val queuedRequests: Int,
        ) : WorkerMailboxState

        data class Closing(
            val job: Job,
        ) : WorkerMailboxState

        data object Closed : WorkerMailboxState
    }

    private data class WorkerHandle(
        val key: ReplyQueueKey,
        val workerId: Long,
        val createdAtMs: Long,
        val mailboxState: WorkerMailboxState,
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

        data class WorkerDequeued(
            val key: ReplyQueueKey,
            val workerId: Long,
        ) : AdmissionCommand
    }

    private data class WorkerClosePlan(
        val allowed: Boolean,
        val workers: List<WorkerHandle> = emptyList(),
        val workerScope: CoroutineScope? = null,
    )

    private enum class WorkerEnqueueResult {
        ACCEPTED,
        CLOSED,
        FULL,
    }

    private val commands = Channel<AdmissionCommand>(commandChannelCapacity)
    private val commandScope = buildCoroutineScope()
    private val requestProcessor = initialRequestProcessor

    private var workerRegistry = mutableMapOf<ReplyQueueKey, WorkerHandle>()
    private var closingWorkers = mutableMapOf<Long, WorkerHandle>()
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

    suspend fun startSuspend() {
        val started = dispatchSuspend<AdmissionCommand.Start, Boolean> { AdmissionCommand.Start(it) }
        if (started) {
            IrisLogger.debug("[ReplyAdmissionService] started")
        }
    }

    suspend fun restartSuspend() {
        IrisLogger.info("[ReplyAdmissionService] Restarting...")
        val plan =
            dispatchSuspend<AdmissionCommand.Restart, WorkerClosePlan> {
                AdmissionCommand.Restart(it)
            }
        if (!plan.allowed) {
            return
        }
        closeWorkersSuspend(plan.workers)
        plan.workerScope?.cancel()
        dispatchSuspend<AdmissionCommand.RestartCompleted, Unit> {
            AdmissionCommand.RestartCompleted(it)
        }
        IrisLogger.info("[ReplyAdmissionService] Restart complete")
    }

    suspend fun shutdownSuspend() {
        IrisLogger.info("[ReplyAdmissionService] Shutting down...")
        val plan =
            dispatchSuspend<AdmissionCommand.Shutdown, WorkerClosePlan> {
                AdmissionCommand.Shutdown(it)
            }
        closeWorkersSuspend(plan.workers)
        plan.workerScope?.cancel()
        IrisLogger.info("[ReplyAdmissionService] Shutdown complete")
    }

    suspend fun enqueueSuspend(
        key: ReplyQueueKey,
        request: PipelineRequest,
    ): ReplyAdmissionResult =
        dispatchSuspend<AdmissionCommand.Enqueue, ReplyAdmissionResult> {
            AdmissionCommand.Enqueue(key, request, it)
        }

    internal suspend fun debugSnapshotSuspend(): ReplyAdmissionDebugSnapshot =
        dispatchSuspend<AdmissionCommand.DebugSnapshot, ReplyAdmissionDebugSnapshot> {
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
            is AdmissionCommand.WorkerDequeued -> handleWorkerDequeued(command)
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

        val workers =
            (workerRegistry.values + closingWorkers.values)
                .distinctBy(WorkerHandle::workerId)
                .map(::markClosing)
        val oldWorkerScope = workerScope
        workerRegistry.clear()
        closingWorkers = workers.associateBy(WorkerHandle::workerId).toMutableMap()
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
        closingWorkers.clear()
        command.reply.complete(Unit)
    }

    private fun handleShutdown(command: AdmissionCommand.Shutdown) {
        val workers =
            (workerRegistry.values + closingWorkers.values)
                .distinctBy(WorkerHandle::workerId)
                .map(::markClosing)
        val oldWorkerScope = workerScope
        workerRegistry.clear()
        closingWorkers = workers.associateBy(WorkerHandle::workerId).toMutableMap()
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
                enqueueToWorkerLoop(command.key, command.request)
            }
        command.reply.complete(result)
    }

    private fun handleDebugSnapshot(command: AdmissionCommand.DebugSnapshot) {
        command.reply.complete(
            ReplyAdmissionDebugSnapshot(
                lifecycle = lifecycle,
                activeWorkers = workerRegistry.size,
                closingWorkers = closingWorkers.size,
                workers =
                    (workerRegistry.values + closingWorkers.values)
                        .distinctBy(WorkerHandle::workerId)
                        .map(::toWorkerDebug)
                        .sortedWith(compareBy({ it.key.chatId.value }, { it.key.threadId?.value ?: -1L }, { it.workerId })),
            ),
        )
    }

    private fun handleWorkerClosed(command: AdmissionCommand.WorkerClosed) {
        val current = workerRegistry[command.key]
        if (current?.workerId == command.workerId) {
            workerRegistry.remove(command.key)
        }
        closingWorkers.remove(command.workerId)
    }

    private fun handleWorkerDequeued(command: AdmissionCommand.WorkerDequeued) {
        val current = workerRegistry[command.key] ?: return
        if (current.workerId != command.workerId) {
            return
        }
        val mailboxState = current.mailboxState as? WorkerMailboxState.Open ?: return
        workerRegistry[command.key] =
            current.copy(
                mailboxState =
                    mailboxState.copy(
                        queuedRequests = (mailboxState.queuedRequests - 1).coerceAtLeast(0),
                    ),
            )
    }

    private fun getOrCreateWorkerInternal(key: ReplyQueueKey): WorkerHandle? {
        workerRegistry[key]?.let { return it }
        if (workerRegistry.size >= maxWorkers) {
            return null
        }
        return launchWorkerInternal(key).also { workerRegistry[key] = it }
    }

    private fun launchWorkerInternal(key: ReplyQueueKey): WorkerHandle {
        val channel = Channel<PipelineRequest>(perWorkerQueueCapacity)
        val workerId = nextWorkerId++
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
                        commands.trySend(
                            AdmissionCommand.WorkerDequeued(
                                key = key,
                                workerId = workerId,
                            ),
                        )
                        try {
                            requestProcessor(request)
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
        return WorkerHandle(
            key = key,
            workerId = workerId,
            createdAtMs = clock(),
            mailboxState =
                WorkerMailboxState.Open(
                    channel = channel,
                    job = job,
                    queuedRequests = 0,
                ),
        )
    }

    private fun enqueueToWorkerLoop(
        key: ReplyQueueKey,
        request: PipelineRequest,
    ): ReplyAdmissionResult {
        repeat(2) {
            val worker =
                getOrCreateWorkerInternal(key)
                    ?: return ReplyAdmissionResult(
                        ReplyAdmissionStatus.QUEUE_FULL,
                        "too many active reply workers",
                    )
            when (tryEnqueueToWorker(worker, request)) {
                WorkerEnqueueResult.ACCEPTED ->
                    return ReplyAdmissionResult(ReplyAdmissionStatus.ACCEPTED)
                WorkerEnqueueResult.FULL -> {
                    IrisLogger.error("[ReplyAdmissionService] Queue full for worker($key)")
                    return ReplyAdmissionResult(ReplyAdmissionStatus.QUEUE_FULL, "reply queue is full")
                }
                WorkerEnqueueResult.CLOSED -> replaceClosedWorkerInternal(key, worker)
            }
        }
        return ReplyAdmissionResult(ReplyAdmissionStatus.QUEUE_FULL, "reply queue is full")
    }

    private fun tryEnqueueToWorker(
        worker: WorkerHandle,
        request: PipelineRequest,
    ): WorkerEnqueueResult {
        val mailboxState = worker.mailboxState as? WorkerMailboxState.Open ?: return WorkerEnqueueResult.CLOSED
        val sendResult = mailboxState.channel.trySend(request)
        return when {
            sendResult.isSuccess -> {
                workerRegistry[worker.key]?.takeIf { it.workerId == worker.workerId }?.let { current ->
                    workerRegistry[worker.key] =
                        current.copy(
                            mailboxState =
                                mailboxState.copy(
                                    queuedRequests = mailboxState.queuedRequests + 1,
                                ),
                        )
                }
                IrisLogger.debug("[ReplyAdmissionService] Request queued to worker(${worker.key})")
                WorkerEnqueueResult.ACCEPTED
            }
            sendResult.isClosed -> WorkerEnqueueResult.CLOSED
            else -> WorkerEnqueueResult.FULL
        }
    }

    private fun replaceClosedWorkerInternal(
        key: ReplyQueueKey,
        worker: WorkerHandle,
    ) {
        val current = workerRegistry[key]
        if (current?.workerId == worker.workerId) {
            workerRegistry.remove(key)
        }
    }

    private suspend fun closeWorkersSuspend(workers: List<WorkerHandle>) {
        val gracefulWaitMs = minOf(1_000L, shutdownTimeoutMs)
        workers.forEach { worker ->
            (worker.mailboxState as? WorkerMailboxState.Open)?.channel?.close()
        }
        workers.forEach { worker ->
            val job = worker.job() ?: return@forEach
            val finishedGracefully = withTimeoutOrNull(gracefulWaitMs) { job.join() } != null
            if (!finishedGracefully) {
                if (job.isActive) {
                    job.cancel()
                }
                val cancelWaitMs = (shutdownTimeoutMs - gracefulWaitMs).coerceAtLeast(0L)
                withTimeoutOrNull(cancelWaitMs) { job.join() }
                    ?: IrisLogger.error("[ReplyAdmissionService] Worker(${worker.key}) cancel timed out; abandoning")
            }
        }
    }

    private fun markClosing(worker: WorkerHandle): WorkerHandle =
        when (val mailboxState = worker.mailboxState) {
            is WorkerMailboxState.Open -> worker.copy(mailboxState = WorkerMailboxState.Closing(mailboxState.job))
            is WorkerMailboxState.Closing -> worker
            WorkerMailboxState.Closed -> worker
        }

    private fun WorkerHandle.job(): Job? =
        when (val mailboxState = mailboxState) {
            is WorkerMailboxState.Open -> mailboxState.job
            is WorkerMailboxState.Closing -> mailboxState.job
            WorkerMailboxState.Closed -> null
        }

    private fun toWorkerDebug(worker: WorkerHandle): ReplyAdmissionWorkerDebug {
        val mailboxState = worker.mailboxState
        val queueDepth = (mailboxState as? WorkerMailboxState.Open)?.queuedRequests ?: 0
        val stateName =
            when (mailboxState) {
                is WorkerMailboxState.Open -> "OPEN"
                is WorkerMailboxState.Closing -> "CLOSING"
                WorkerMailboxState.Closed -> "CLOSED"
            }
        return ReplyAdmissionWorkerDebug(
            key = worker.key,
            workerId = worker.workerId,
            ageMs = (clock() - worker.createdAtMs).coerceAtLeast(0L),
            queueDepth = queueDepth,
            mailboxState = stateName,
        )
    }

    private suspend fun <C : AdmissionCommand, T> dispatchSuspend(build: (CompletableDeferred<T>) -> C): T {
        val reply = CompletableDeferred<T>()
        commands.send(build(reply))
        return reply.await()
    }

    private fun buildCoroutineScope(): CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher)
}
