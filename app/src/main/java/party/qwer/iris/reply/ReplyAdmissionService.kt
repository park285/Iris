package party.qwer.iris.reply

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import party.qwer.iris.IrisLogger
import party.qwer.iris.ReplyAdmissionResult
import party.qwer.iris.ReplyAdmissionStatus
import party.qwer.iris.ReplyQueueKey

internal class ReplyAdmissionService(
    private val maxWorkers: Int = 16,
    private val perWorkerQueueCapacity: Int = 16,
    private val workerIdleTimeoutMs: Long = 60_000L,
    private val shutdownTimeoutMs: Long = 10_000L,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val commandChannelCapacity: Int = Channel.BUFFERED,
    private val clock: () -> Long = System::currentTimeMillis,
    initialJobProcessor: suspend (ReplyLaneJob) -> Unit = { job ->
        job.prepare()
        job.send()
    },
) {
    private sealed interface WorkerMailboxState {
        data class Open(
            val channel: Channel<ReplyLaneJob>,
            val job: Job,
            val queuedJobs: Int,
        ) : WorkerMailboxState

        data class Closing(
            val channel: Channel<ReplyLaneJob>,
            val job: Job,
            val queuedJobs: Int,
        ) : WorkerMailboxState
    }

    private enum class WorkerCloseReason {
        IDLE_TIMEOUT,
        CHANNEL_CLOSED,
        CANCELLED,
        FAILED,
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
            val reply: CompletableDeferred<DetachedWorkersPlan>,
        ) : AdmissionCommand

        data class RestartCompleted(
            val reply: CompletableDeferred<Unit>,
        ) : AdmissionCommand

        data class Shutdown(
            val reply: CompletableDeferred<DetachedWorkersPlan>,
        ) : AdmissionCommand

        data class ShutdownCompleted(
            val reply: CompletableDeferred<Unit>,
        ) : AdmissionCommand

        data class Enqueue(
            val key: ReplyQueueKey,
            val job: ReplyLaneJob,
            val reply: CompletableDeferred<ReplyAdmissionResult>,
        ) : AdmissionCommand

        data class DebugSnapshot(
            val reply: CompletableDeferred<ReplyAdmissionDebugSnapshot>,
        ) : AdmissionCommand

        data class WorkerClosed(
            val key: ReplyQueueKey,
            val workerId: Long,
            val reason: WorkerCloseReason,
        ) : AdmissionCommand

        data class WorkerDequeued(
            val key: ReplyQueueKey,
            val workerId: Long,
        ) : AdmissionCommand
    }

    private data class DetachedWorkersPlan(
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
    private val actorJob: Job
    private val jobProcessor = initialJobProcessor

    @Volatile
    private var commandActorClosed = false

    private var workerRegistry = mutableMapOf<ReplyQueueKey, WorkerHandle>()
    private var closingWorkers = mutableMapOf<Long, WorkerHandle>()
    private var workerScope = buildCoroutineScope()
    private var lifecycle = ReplyAdmissionLifecycle.STOPPED
    private var nextWorkerId = 0L

    init {
        actorJob =
            commandScope.launch {
                try {
                    for (command in commands) {
                        handleCommandSafely(command)
                    }
                } finally {
                    commandActorClosed = true
                }
            }
    }

    suspend fun startSuspend() {
        if (commandActorClosed) {
            IrisLogger.error("[ReplyAdmissionService] Cannot start after actor shutdown")
            return
        }
        val started =
            dispatchSuspend<AdmissionCommand.Start, Boolean>(
                onActorClosed = { false },
            ) { AdmissionCommand.Start(it) }
        if (started) {
            IrisLogger.debug("[ReplyAdmissionService] started")
        }
    }

    suspend fun restartSuspend() {
        if (commandActorClosed) {
            IrisLogger.error("[ReplyAdmissionService] Cannot restart after actor shutdown")
            return
        }
        IrisLogger.info("[ReplyAdmissionService] Restarting...")
        val plan =
            dispatchSuspend<AdmissionCommand.Restart, DetachedWorkersPlan>(
                onActorClosed = { DetachedWorkersPlan(allowed = false) },
            ) {
                AdmissionCommand.Restart(it)
            }
        if (!plan.allowed) {
            return
        }
        closeWorkersSuspend(plan.workers)
        plan.workerScope?.cancel()
        dispatchSuspend<AdmissionCommand.RestartCompleted, Unit>(
            onActorClosed = { Unit },
        ) {
            AdmissionCommand.RestartCompleted(it)
        }
        IrisLogger.info("[ReplyAdmissionService] Restart complete")
    }

    suspend fun shutdownSuspend() {
        if (commandActorClosed) {
            return
        }
        IrisLogger.info("[ReplyAdmissionService] Shutting down...")
        val plan =
            dispatchSuspend<AdmissionCommand.Shutdown, DetachedWorkersPlan>(
                onActorClosed = { DetachedWorkersPlan(allowed = false) },
            ) {
                AdmissionCommand.Shutdown(it)
            }
        closeWorkersSuspend(plan.workers)
        plan.workerScope?.cancel()
        dispatchSuspend<AdmissionCommand.ShutdownCompleted, Unit>(
            onActorClosed = { Unit },
        ) {
            AdmissionCommand.ShutdownCompleted(it)
        }
        closeCommandActorAndWait()
        IrisLogger.info("[ReplyAdmissionService] Shutdown complete")
    }

    suspend fun enqueueSuspend(
        key: ReplyQueueKey,
        job: ReplyLaneJob,
    ): ReplyAdmissionResult {
        if (commandActorClosed) {
            return ReplyAdmissionResult(ReplyAdmissionStatus.SHUTDOWN, "reply sender unavailable")
        }
        return dispatchSuspend<AdmissionCommand.Enqueue, ReplyAdmissionResult>(
            onActorClosed = {
                ReplyAdmissionResult(ReplyAdmissionStatus.SHUTDOWN, "reply sender unavailable")
            },
        ) { AdmissionCommand.Enqueue(key, job, it) }
    }

    internal suspend fun debugSnapshotSuspend(): ReplyAdmissionDebugSnapshot =
        if (commandActorClosed) {
            currentDebugSnapshot()
        } else {
            dispatchSuspend<AdmissionCommand.DebugSnapshot, ReplyAdmissionDebugSnapshot>(
                onActorClosed = { currentDebugSnapshot() },
            ) {
                AdmissionCommand.DebugSnapshot(it)
            }
        }

    private suspend fun handleCommandSafely(command: AdmissionCommand) {
        try {
            handleCommand(command)
        } catch (error: Throwable) {
            IrisLogger.error("[ReplyAdmissionService] actor command failed: ${error.message}", error)
            failCommand(command, error)
        }
    }

    private fun failCommand(
        command: AdmissionCommand,
        error: Throwable,
    ) {
        when (command) {
            is AdmissionCommand.Start -> command.reply.complete(false)
            is AdmissionCommand.Restart -> command.reply.complete(DetachedWorkersPlan(allowed = false))
            is AdmissionCommand.RestartCompleted -> command.reply.complete(Unit)
            is AdmissionCommand.Shutdown -> command.reply.complete(DetachedWorkersPlan(allowed = false))
            is AdmissionCommand.ShutdownCompleted -> command.reply.complete(Unit)
            is AdmissionCommand.Enqueue ->
                command.reply.complete(
                    ReplyAdmissionResult(
                        ReplyAdmissionStatus.SHUTDOWN,
                        error.message ?: "reply sender unavailable",
                    ),
                )
            is AdmissionCommand.DebugSnapshot -> command.reply.complete(currentDebugSnapshot())
            is AdmissionCommand.WorkerClosed -> Unit
            is AdmissionCommand.WorkerDequeued -> Unit
        }
    }

    private suspend fun handleCommand(command: AdmissionCommand) {
        when (command) {
            is AdmissionCommand.Start -> handleStart(command)
            is AdmissionCommand.Restart -> handleRestart(command)
            is AdmissionCommand.RestartCompleted -> handleRestartCompleted(command)
            is AdmissionCommand.Shutdown -> handleShutdown(command)
            is AdmissionCommand.ShutdownCompleted -> handleShutdownCompleted(command)
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
            command.reply.complete(DetachedWorkersPlan(allowed = false))
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
            DetachedWorkersPlan(
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
            DetachedWorkersPlan(
                allowed = true,
                workers = workers,
                workerScope = oldWorkerScope,
            ),
        )
    }

    private fun handleShutdownCompleted(command: AdmissionCommand.ShutdownCompleted) {
        closingWorkers.clear()
        command.reply.complete(Unit)
    }

    private suspend fun handleEnqueue(command: AdmissionCommand.Enqueue) {
        val result =
            if (lifecycle != ReplyAdmissionLifecycle.RUNNING) {
                ReplyAdmissionResult(ReplyAdmissionStatus.SHUTDOWN, "reply sender unavailable")
            } else {
                enqueueToWorkerLoop(command.key, command.job)
            }
        if (result.status != ReplyAdmissionStatus.ACCEPTED) {
            runCatching { command.job.abort() }
        }
        command.reply.complete(result)
    }

    private fun handleDebugSnapshot(command: AdmissionCommand.DebugSnapshot) {
        command.reply.complete(currentDebugSnapshot())
    }

    private fun handleWorkerClosed(command: AdmissionCommand.WorkerClosed) {
        val current = workerRegistry[command.key]
        if (current?.workerId == command.workerId) {
            workerRegistry.remove(command.key)
        }
        closingWorkers.remove(command.workerId)
    }

    private fun handleWorkerDequeued(command: AdmissionCommand.WorkerDequeued) {
        workerRegistry[command.key]
            ?.takeIf { it.workerId == command.workerId }
            ?.let { current ->
                val mailboxState = current.mailboxState as? WorkerMailboxState.Open ?: return@let
                workerRegistry[command.key] =
                    current.copy(
                        mailboxState =
                            mailboxState.copy(
                                queuedJobs = (mailboxState.queuedJobs - 1).coerceAtLeast(0),
                            ),
                    )
            }

        closingWorkers[command.workerId]?.let { current ->
            val mailboxState = current.mailboxState as? WorkerMailboxState.Closing ?: return@let
            closingWorkers[command.workerId] =
                current.copy(
                    mailboxState =
                        mailboxState.copy(
                            queuedJobs = (mailboxState.queuedJobs - 1).coerceAtLeast(0),
                        ),
                )
        }
    }

    private fun getOrCreateWorkerInternal(key: ReplyQueueKey): WorkerHandle? {
        workerRegistry[key]?.let { return it }
        if (workerRegistry.size >= maxWorkers) {
            return null
        }
        return launchWorkerInternal(key).also { workerRegistry[key] = it }
    }

    private fun launchWorkerInternal(key: ReplyQueueKey): WorkerHandle {
        val channel = Channel<ReplyLaneJob>(perWorkerQueueCapacity)
        val workerId = nextWorkerId++
        val job =
            workerScope.launch {
                var closeReason = WorkerCloseReason.CHANNEL_CLOSED
                try {
                    while (true) {
                        val receiveResult =
                            withTimeoutOrNull(workerIdleTimeoutMs) {
                                channel.receiveCatching()
                            }
                        if (receiveResult == null) {
                            closeReason = WorkerCloseReason.IDLE_TIMEOUT
                            break
                        }
                        val job = receiveResult.getOrNull() ?: break
                        commands.trySend(
                            AdmissionCommand.WorkerDequeued(
                                key = key,
                                workerId = workerId,
                            ),
                        )
                        try {
                            jobProcessor(job)
                        } catch (e: CancellationException) {
                            closeReason = WorkerCloseReason.CANCELLED
                            throw e
                        } catch (e: Exception) {
                            closeReason = WorkerCloseReason.FAILED
                            IrisLogger.error("[ReplyAdmissionService] worker($key) error: ${e.message}", e)
                        }
                    }
                } finally {
                    channel.close()
                    commands.trySend(
                        AdmissionCommand.WorkerClosed(
                            key = key,
                            workerId = workerId,
                            reason = closeReason,
                        ),
                    )
                    IrisLogger.debug("[ReplyAdmissionService] worker($key) terminated (${closeReason.name})")
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
                    queuedJobs = 0,
                ),
        )
    }

    private fun enqueueToWorkerLoop(
        key: ReplyQueueKey,
        job: ReplyLaneJob,
    ): ReplyAdmissionResult {
        repeat(2) {
            val worker =
                getOrCreateWorkerInternal(key)
                    ?: return ReplyAdmissionResult(
                        ReplyAdmissionStatus.QUEUE_FULL,
                        "too many active reply workers",
                    )
            when (tryEnqueueToWorker(worker, job)) {
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
        job: ReplyLaneJob,
    ): WorkerEnqueueResult {
        val mailboxState = worker.mailboxState as? WorkerMailboxState.Open ?: return WorkerEnqueueResult.CLOSED
        val sendResult = mailboxState.channel.trySend(job)
        return when {
            sendResult.isSuccess -> {
                workerRegistry[worker.key]?.takeIf { it.workerId == worker.workerId }?.let { current ->
                    workerRegistry[worker.key] =
                        current.copy(
                            mailboxState =
                                mailboxState.copy(
                                    queuedJobs = mailboxState.queuedJobs + 1,
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
            worker.channel()?.close()
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
            is WorkerMailboxState.Open ->
                worker.copy(
                    mailboxState =
                        WorkerMailboxState.Closing(
                            channel = mailboxState.channel,
                            job = mailboxState.job,
                            queuedJobs = mailboxState.queuedJobs,
                        ),
                )
            is WorkerMailboxState.Closing -> worker
        }

    private fun WorkerHandle.channel(): Channel<ReplyLaneJob>? =
        when (val mailboxState = mailboxState) {
            is WorkerMailboxState.Open -> mailboxState.channel
            is WorkerMailboxState.Closing -> mailboxState.channel
        }

    private fun WorkerHandle.job(): Job? =
        when (val mailboxState = mailboxState) {
            is WorkerMailboxState.Open -> mailboxState.job
            is WorkerMailboxState.Closing -> mailboxState.job
        }

    private fun toWorkerDebug(worker: WorkerHandle): ReplyAdmissionWorkerDebug {
        val mailboxState = worker.mailboxState
        val queueDepth =
            when (mailboxState) {
                is WorkerMailboxState.Open -> mailboxState.queuedJobs
                is WorkerMailboxState.Closing -> mailboxState.queuedJobs
            }
        val stateName =
            when (mailboxState) {
                is WorkerMailboxState.Open -> "OPEN"
                is WorkerMailboxState.Closing -> "CLOSING"
            }
        return ReplyAdmissionWorkerDebug(
            key = worker.key,
            workerId = worker.workerId,
            ageMs = (clock() - worker.createdAtMs).coerceAtLeast(0L),
            queueDepth = queueDepth,
            mailboxState = stateName,
        )
    }

    private suspend fun <C : AdmissionCommand, T> dispatchSuspend(
        onActorClosed: () -> T,
        build: (CompletableDeferred<T>) -> C,
    ): T {
        if (commandActorClosed) {
            return onActorClosed()
        }
        val reply = CompletableDeferred<T>()
        try {
            commands.send(build(reply))
        } catch (_: ClosedSendChannelException) {
            return onActorClosed()
        }
        return reply.await()
    }

    private fun currentDebugSnapshot(): ReplyAdmissionDebugSnapshot =
        ReplyAdmissionDebugSnapshot(
            lifecycle = lifecycle,
            activeWorkers = workerRegistry.size,
            closingWorkers = closingWorkers.size,
            workers =
                (workerRegistry.values + closingWorkers.values)
                    .distinctBy(WorkerHandle::workerId)
                    .map(::toWorkerDebug)
                    .sortedWith(compareBy({ it.key.chatId.value }, { it.key.threadId?.value ?: -1L }, { it.workerId })),
        )

    private suspend fun closeCommandActorAndWait() {
        if (commandActorClosed) {
            return
        }
        commands.close()
        actorJob.join()
        commandScope.cancel()
    }

    private fun buildCoroutineScope(): CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher)
}
