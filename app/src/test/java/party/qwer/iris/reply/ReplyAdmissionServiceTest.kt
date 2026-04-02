package party.qwer.iris.reply

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import party.qwer.iris.ReplyAdmissionStatus
import party.qwer.iris.ReplyQueueKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ReplyAdmissionServiceTest {
    @Test
    fun `rejected enqueue aborts request inside admission`() {
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val service =
                ReplyAdmissionService(
                    maxWorkers = 1,
                    perWorkerQueueCapacity = 1,
                    workerIdleTimeoutMs = 60_000L,
                    dispatcher = dispatcher,
                )
            service.startSuspend()

            val key = ReplyQueueKey(chatId = 1L, threadId = null)
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val blockingRequest = controlledReplyLaneJob(started, release)
            assertEquals(ReplyAdmissionStatus.ACCEPTED, service.enqueueSuspend(key, blockingRequest).status)

            advanceUntilIdle()
            started.await()
            assertEquals(ReplyAdmissionStatus.ACCEPTED, service.enqueueSuspend(key, stubReplyLaneJob()).status)

            val aborted = CompletableDeferred<Unit>()
            val rejected =
                service.enqueueSuspend(
                    key,
                    trackingReplyLaneJob(onAbort = { aborted.complete(Unit) }),
                )

            assertEquals(ReplyAdmissionStatus.QUEUE_FULL, rejected.status)
            advanceUntilIdle()
            assertTrue(aborted.isCompleted, "admission should own rejection cleanup")

            release.complete(Unit)
            advanceUntilIdle()
            service.shutdownSuspend()
        }
    }

    @Test
    fun `exposes explicit lifecycle ownership`() {
        val service = ReplyAdmissionService()

        assertEquals(ReplyAdmissionLifecycle.STOPPED, service.debugSnapshot().lifecycle)

        service.start()
        assertEquals(ReplyAdmissionLifecycle.RUNNING, service.debugSnapshot().lifecycle)

        service.restart()
        assertEquals(ReplyAdmissionLifecycle.RUNNING, service.debugSnapshot().lifecycle)

        service.shutdown()
        assertEquals(ReplyAdmissionLifecycle.TERMINATED, service.debugSnapshot().lifecycle)
    }

    @Test
    fun `accepts request when started and worker available`() {
        val service =
            ReplyAdmissionService(
                maxWorkers = 16,
                perWorkerQueueCapacity = 16,
            )
        service.start()

        val result =
            service.enqueue(
                key = ReplyQueueKey(chatId = 1L, threadId = null),
                request = stubReplyLaneJob(),
            )

        assertEquals(ReplyAdmissionStatus.ACCEPTED, result.status)
        service.shutdown()
    }

    @Test
    fun `rejects when not started`() {
        val service =
            ReplyAdmissionService(
                maxWorkers = 16,
                perWorkerQueueCapacity = 16,
            )

        val result =
            service.enqueue(
                key = ReplyQueueKey(chatId = 1L, threadId = null),
                request = stubReplyLaneJob(),
            )

        assertEquals(ReplyAdmissionStatus.SHUTDOWN, result.status)
    }

    @Test
    fun `rejects after shutdown`() {
        val service =
            ReplyAdmissionService(
                maxWorkers = 16,
                perWorkerQueueCapacity = 16,
            )
        service.start()
        service.shutdown()

        val result =
            service.enqueue(
                key = ReplyQueueKey(chatId = 1L, threadId = null),
                request = stubReplyLaneJob(),
            )

        assertEquals(ReplyAdmissionStatus.SHUTDOWN, result.status)
    }

    @Test
    fun `rejects when max workers exceeded`() {
        val service =
            ReplyAdmissionService(
                maxWorkers = 2,
                perWorkerQueueCapacity = 16,
            )
        service.start()

        val r1 = service.enqueue(ReplyQueueKey(chatId = 1L, threadId = null), stubReplyLaneJob())
        val r2 = service.enqueue(ReplyQueueKey(chatId = 2L, threadId = null), stubReplyLaneJob())
        val r3 = service.enqueue(ReplyQueueKey(chatId = 3L, threadId = null), stubReplyLaneJob())

        assertEquals(ReplyAdmissionStatus.ACCEPTED, r1.status)
        assertEquals(ReplyAdmissionStatus.ACCEPTED, r2.status)
        assertEquals(ReplyAdmissionStatus.QUEUE_FULL, r3.status)
        service.shutdown()
    }

    @Test
    fun `reuses existing worker for same key`() {
        val service =
            ReplyAdmissionService(
                maxWorkers = 2,
                perWorkerQueueCapacity = 16,
            )
        service.start()

        val r1 = service.enqueue(ReplyQueueKey(chatId = 1L, threadId = null), stubReplyLaneJob())
        val r2 = service.enqueue(ReplyQueueKey(chatId = 1L, threadId = null), stubReplyLaneJob())

        assertEquals(ReplyAdmissionStatus.ACCEPTED, r1.status)
        assertEquals(ReplyAdmissionStatus.ACCEPTED, r2.status)
        service.shutdown()
    }

    @Test
    fun `rejects when per-worker queue is full`() {
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val service =
                ReplyAdmissionService(
                    maxWorkers = 16,
                    perWorkerQueueCapacity = 1,
                    workerIdleTimeoutMs = 60_000L,
                    dispatcher = dispatcher,
                )
            service.startSuspend()

            val key = ReplyQueueKey(chatId = 1L, threadId = null)
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val blockingRequest = controlledReplyLaneJob(started, release)
            val r1 = service.enqueueSuspend(key, blockingRequest)
            assertEquals(ReplyAdmissionStatus.ACCEPTED, r1.status)

            advanceUntilIdle()
            started.await()
            assertEquals(1, service.debugSnapshotSuspend().activeWorkers)

            val r2 = service.enqueueSuspend(key, stubReplyLaneJob())
            assertEquals(ReplyAdmissionStatus.ACCEPTED, r2.status)

            val r3 = service.enqueueSuspend(key, stubReplyLaneJob())
            assertEquals(ReplyAdmissionStatus.QUEUE_FULL, r3.status)

            release.complete(Unit)
            advanceUntilIdle()
            service.shutdownSuspend()
        }
    }

    @Test
    fun `restart clears workers and accepts new requests`() {
        val service =
            ReplyAdmissionService(
                maxWorkers = 16,
                perWorkerQueueCapacity = 16,
            )
        service.start()

        val r1 = service.enqueue(ReplyQueueKey(chatId = 1L, threadId = null), stubReplyLaneJob())
        assertEquals(ReplyAdmissionStatus.ACCEPTED, r1.status)

        service.restart()

        val r2 = service.enqueue(ReplyQueueKey(chatId = 1L, threadId = null), stubReplyLaneJob())
        assertEquals(ReplyAdmissionStatus.ACCEPTED, r2.status)
        service.shutdown()
    }

    @Test
    fun `shutdown closes closing worker channel without waiting for idle timeout`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val service =
                ReplyAdmissionService(
                    maxWorkers = 1,
                    perWorkerQueueCapacity = 1,
                    workerIdleTimeoutMs = 60_000L,
                    dispatcher = dispatcher,
                )
            service.startSuspend()

            val key = ReplyQueueKey(chatId = 1L, threadId = null)
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var drainedQueuedJobs = 0

            assertEquals(ReplyAdmissionStatus.ACCEPTED, service.enqueueSuspend(key, controlledReplyLaneJob(started, release)).status)
            runCurrent()
            started.await()
            assertEquals(ReplyAdmissionStatus.ACCEPTED, service.enqueueSuspend(key, countingReplyLaneJob { drainedQueuedJobs += 1 }).status)

            val shutdownJob =
                backgroundScope.launch {
                    service.shutdownSuspend()
                }
            runCurrent()

            val closingSnapshot = service.debugSnapshotSuspend()
            assertEquals(ReplyAdmissionLifecycle.TERMINATED, closingSnapshot.lifecycle)
            assertEquals(0, closingSnapshot.activeWorkers)
            assertEquals(1, closingSnapshot.closingWorkers)
            assertEquals("CLOSING", closingSnapshot.workers.single().mailboxState)
            assertEquals(1, closingSnapshot.workers.single().queueDepth)

            release.complete(Unit)
            runCurrent()

            assertEquals(1, drainedQueuedJobs)
            assertTrue(shutdownJob.isCompleted, "shutdown should finish after channel close without idle timeout")
        }

    @Test
    fun `restart drains queued jobs and resumes with a fresh worker scope`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val service =
                ReplyAdmissionService(
                    maxWorkers = 1,
                    perWorkerQueueCapacity = 1,
                    workerIdleTimeoutMs = 60_000L,
                    dispatcher = dispatcher,
                )
            service.startSuspend()

            val key = ReplyQueueKey(chatId = 1L, threadId = null)
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var drainedQueuedJobs = 0

            assertEquals(ReplyAdmissionStatus.ACCEPTED, service.enqueueSuspend(key, controlledReplyLaneJob(started, release)).status)
            runCurrent()
            started.await()
            assertEquals(ReplyAdmissionStatus.ACCEPTED, service.enqueueSuspend(key, countingReplyLaneJob { drainedQueuedJobs += 1 }).status)

            val restartJob =
                backgroundScope.launch {
                    service.restartSuspend()
                }
            runCurrent()

            val closingSnapshot = service.debugSnapshotSuspend()
            assertEquals(ReplyAdmissionLifecycle.STOPPED, closingSnapshot.lifecycle)
            assertEquals(0, closingSnapshot.activeWorkers)
            assertEquals(1, closingSnapshot.closingWorkers)
            assertEquals("CLOSING", closingSnapshot.workers.single().mailboxState)
            assertEquals(1, closingSnapshot.workers.single().queueDepth)

            release.complete(Unit)
            runCurrent()

            assertEquals(1, drainedQueuedJobs)
            assertTrue(restartJob.isCompleted, "restart should finish after draining a closed channel")

            assertEquals(ReplyAdmissionStatus.ACCEPTED, service.enqueueSuspend(key, stubReplyLaneJob()).status)
            runCurrent()

            val restartedSnapshot = service.debugSnapshotSuspend()
            assertEquals(ReplyAdmissionLifecycle.RUNNING, restartedSnapshot.lifecycle)
            assertEquals(1, restartedSnapshot.activeWorkers)
            assertEquals(0, restartedSnapshot.closingWorkers)

            service.shutdownSuspend()
        }

    @Test
    fun `stale worker retry creates new worker`() {
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val service =
                ReplyAdmissionService(
                    maxWorkers = 16,
                    perWorkerQueueCapacity = 16,
                    workerIdleTimeoutMs = 50L,
                    dispatcher = dispatcher,
                )
            service.startSuspend()

            val key = ReplyQueueKey(chatId = 1L, threadId = null)
            val r1 = service.enqueueSuspend(key, stubReplyLaneJob())
            assertEquals(ReplyAdmissionStatus.ACCEPTED, r1.status)

            advanceUntilIdle()
            advanceTimeBy(50L)
            advanceUntilIdle()
            assertEquals(0, service.debugSnapshotSuspend().activeWorkers)

            val r2 = service.enqueueSuspend(key, stubReplyLaneJob())
            assertEquals(ReplyAdmissionStatus.ACCEPTED, r2.status)
            service.shutdownSuspend()
        }
    }

    @Test
    fun `idle worker release frees worker slot for a different key`() {
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val service =
                ReplyAdmissionService(
                    maxWorkers = 1,
                    perWorkerQueueCapacity = 16,
                    workerIdleTimeoutMs = 50L,
                    dispatcher = dispatcher,
                )
            service.startSuspend()

            val firstKey = ReplyQueueKey(chatId = 1L, threadId = null)
            val secondKey = ReplyQueueKey(chatId = 2L, threadId = null)

            assertEquals(ReplyAdmissionStatus.ACCEPTED, service.enqueueSuspend(firstKey, stubReplyLaneJob()).status)
            advanceUntilIdle()
            advanceTimeBy(50L)
            advanceUntilIdle()
            assertEquals(0, service.debugSnapshotSuspend().activeWorkers)

            assertEquals(ReplyAdmissionStatus.ACCEPTED, service.enqueueSuspend(secondKey, stubReplyLaneJob()).status)
            assertEquals(1, service.debugSnapshotSuspend().activeWorkers)
            service.shutdownSuspend()
        }
    }

    @Test
    fun `shutdown keeps service terminated`() {
        val service = ReplyAdmissionService()
        service.start()
        service.shutdown()

        service.restart()

        assertEquals(ReplyAdmissionLifecycle.TERMINATED, service.debugSnapshot().lifecycle)
        assertEquals(
            ReplyAdmissionStatus.SHUTDOWN,
            service.enqueue(ReplyQueueKey(chatId = 1L, threadId = null), stubReplyLaneJob()).status,
        )
    }

    @Test
    fun `enqueueSuspend returns accepted when running`() =
        runBlocking {
            val service =
                ReplyAdmissionService(
                    maxWorkers = 16,
                    perWorkerQueueCapacity = 16,
                )
            service.start()

            val result =
                service.enqueueSuspend(
                    key = ReplyQueueKey(chatId = 1L, threadId = null),
                    job = stubReplyLaneJob(),
                )

            assertEquals(ReplyAdmissionStatus.ACCEPTED, result.status)
            service.shutdown()
        }

    @Test
    fun `debugSnapshotSuspend returns current state`() =
        runBlocking {
            val service = ReplyAdmissionService()
            service.start()

            val snapshot = service.debugSnapshotSuspend()

            assertEquals(ReplyAdmissionLifecycle.RUNNING, snapshot.lifecycle)
            service.shutdown()
        }

    @Test
    fun `concurrent enqueue during shutdown returns shutdown status`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val service =
                ReplyAdmissionService(
                    maxWorkers = 16,
                    perWorkerQueueCapacity = 16,
                    workerIdleTimeoutMs = 60_000L,
                    dispatcher = dispatcher,
                )
            service.startSuspend()

            val key = ReplyQueueKey(chatId = 1L, threadId = null)
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val blockingRequest = controlledReplyLaneJob(started, release)
            assertEquals(ReplyAdmissionStatus.ACCEPTED, service.enqueueSuspend(key, blockingRequest).status)
            advanceUntilIdle()
            started.await()

            // 워커가 활성 상태에서 shutdown 시작
            service.shutdownSuspend()

            // shutdown 후 enqueue는 SHUTDOWN 반환
            val postShutdown = service.enqueueSuspend(key, stubReplyLaneJob())
            assertEquals(ReplyAdmissionStatus.SHUTDOWN, postShutdown.status)

            // 다른 키로도 SHUTDOWN 반환
            val otherKey = ReplyQueueKey(chatId = 2L, threadId = null)
            val otherResult = service.enqueueSuspend(otherKey, stubReplyLaneJob())
            assertEquals(ReplyAdmissionStatus.SHUTDOWN, otherResult.status)

            release.complete(Unit)
            advanceUntilIdle()
        }

    @Test
    @OptIn(DelicateCoroutinesApi::class)
    fun `shutdown closes actor infrastructure`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val service =
                ReplyAdmissionService(
                    workerIdleTimeoutMs = 60_000L,
                    dispatcher = dispatcher,
                )
            service.startSuspend()
            service.shutdownSuspend()

            val commandScopeJob =
                ReplyAdmissionService::class.java.getDeclaredField("commandScope").let { field ->
                    field.isAccessible = true
                    val scope = field.get(service) as kotlinx.coroutines.CoroutineScope
                    checkNotNull(scope.coroutineContext[Job])
                }
            val commands =
                ReplyAdmissionService::class.java.getDeclaredField("commands").let { field ->
                    field.isAccessible = true
                    field.get(service) as Channel<*>
                }

            assertTrue(commandScopeJob.isCancelled)
            assertTrue(commands.isClosedForSend)
        }

    @Test
    fun `enqueue falls back to shutdown when actor channel is already closed`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val service =
                ReplyAdmissionService(
                    workerIdleTimeoutMs = 60_000L,
                    dispatcher = dispatcher,
                )
            service.startSuspend()

            val commands =
                ReplyAdmissionService::class.java.getDeclaredField("commands").let { field ->
                    field.isAccessible = true
                    @Suppress("UNCHECKED_CAST")
                    field.get(service) as Channel<Any?>
                }
            commands.close()

            val result = service.enqueueSuspend(ReplyQueueKey(chatId = 1L, threadId = null), stubReplyLaneJob())

            assertEquals(ReplyAdmissionStatus.SHUTDOWN, result.status)
        }

    @Test
    fun `debugSnapshotSuspend exposes queue depth and worker age`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            var now = 10_000L
            val service =
                ReplyAdmissionService(
                    maxWorkers = 16,
                    perWorkerQueueCapacity = 16,
                    workerIdleTimeoutMs = 60_000L,
                    dispatcher = dispatcher,
                    clock = { now },
                )
            service.startSuspend()

            val key = ReplyQueueKey(chatId = 11L, threadId = 22L)
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val blockingRequest = controlledReplyLaneJob(started, release)
            assertEquals(ReplyAdmissionStatus.ACCEPTED, service.enqueueSuspend(key, blockingRequest).status)

            advanceUntilIdle()
            started.await()
            now += 500L
            assertEquals(ReplyAdmissionStatus.ACCEPTED, service.enqueueSuspend(key, stubReplyLaneJob()).status)

            val snapshot = service.debugSnapshotSuspend()
            assertEquals(ReplyAdmissionLifecycle.RUNNING, snapshot.lifecycle)
            assertEquals(1, snapshot.activeWorkers)
            assertEquals(0, snapshot.closingWorkers)
            assertEquals(1, snapshot.workers.size)
            assertEquals("OPEN", snapshot.workers.single().mailboxState)
            assertEquals(1, snapshot.workers.single().queueDepth)
            assertTrue(snapshot.workers.single().ageMs >= 500L)

            release.complete(Unit)
            advanceUntilIdle()
            service.shutdownSuspend()
        }

    @Test
    fun `shutdown drains buffered command waiters without hanging`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val service =
                ReplyAdmissionService(
                    maxWorkers = 1,
                    perWorkerQueueCapacity = 8,
                    workerIdleTimeoutMs = 60_000L,
                    dispatcher = dispatcher,
                )
            service.startSuspend()

            val key = ReplyQueueKey(chatId = 1L, threadId = null)
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            assertEquals(ReplyAdmissionStatus.ACCEPTED, service.enqueueSuspend(key, controlledReplyLaneJob(started, release)).status)
            runCurrent()
            started.await()

            val pending =
                List(4) {
                    backgroundScope.async {
                        service.enqueueSuspend(key, stubReplyLaneJob())
                    }
                }
            runCurrent()

            val shutdownJob =
                backgroundScope.launch {
                    service.shutdownSuspend()
                }
            runCurrent()

            release.complete(Unit)
            advanceUntilIdle()

            withTimeout(250) {
                shutdownJob.join()
            }
            pending.forEach { deferred ->
                assertTrue(deferred.isCompleted, "buffered enqueue waiter should complete during shutdown drain")
            }
            assertTrue(shutdownJob.isCompleted, "shutdown should finish after buffered command drain")
        }

    @Test
    fun `actor handler failure falls back without killing later commands`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            var failClock = true
            val service =
                ReplyAdmissionService(
                    maxWorkers = 16,
                    perWorkerQueueCapacity = 16,
                    workerIdleTimeoutMs = 60_000L,
                    dispatcher = dispatcher,
                    clock = {
                        check(!failClock) { "clock boom" }
                        10_000L
                    },
                )
            service.startSuspend()

            val failed =
                withTimeout(250) {
                    service.enqueueSuspend(
                        ReplyQueueKey(chatId = 1L, threadId = null),
                        stubReplyLaneJob(),
                    )
                }
            assertEquals(ReplyAdmissionStatus.SHUTDOWN, failed.status)

            failClock = false
            val recovered =
                withTimeout(250) {
                    service.enqueueSuspend(
                        ReplyQueueKey(chatId = 2L, threadId = null),
                        stubReplyLaneJob(),
                    )
                }
            assertEquals(ReplyAdmissionStatus.ACCEPTED, recovered.status)
            service.shutdownSuspend()
        }

    private fun stubReplyLaneJob(blockMs: Long = 0L): ReplyLaneJob =
        object : ReplyLaneJob {
            override val requestId: String? = null

            override suspend fun prepare() {
                if (blockMs > 0L) {
                    kotlinx.coroutines.delay(blockMs)
                }
            }

            override suspend fun send() {}
        }

    private fun controlledReplyLaneJob(
        started: CompletableDeferred<Unit>,
        release: CompletableDeferred<Unit>,
    ): ReplyLaneJob =
        object : ReplyLaneJob {
            override val requestId: String? = null

            override suspend fun prepare() {
                started.complete(Unit)
                release.await()
            }

            override suspend fun send() {}
        }

    private fun trackingReplyLaneJob(onAbort: () -> Unit): ReplyLaneJob =
        object : ReplyLaneJob {
            override val requestId: String? = null

            override suspend fun abort() {
                onAbort()
            }

            override suspend fun send() {}
        }

    private fun countingReplyLaneJob(onSend: () -> Unit): ReplyLaneJob =
        object : ReplyLaneJob {
            override val requestId: String? = null

            override suspend fun send() {
                onSend()
            }
        }
}

private fun ReplyAdmissionService.start() =
    runBlocking {
        startSuspend()
    }

private fun ReplyAdmissionService.restart() =
    runBlocking {
        restartSuspend()
    }

private fun ReplyAdmissionService.shutdown() =
    runBlocking {
        shutdownSuspend()
    }

private fun ReplyAdmissionService.enqueue(
    key: ReplyQueueKey,
    request: ReplyLaneJob,
): party.qwer.iris.ReplyAdmissionResult =
    runBlocking {
        enqueueSuspend(key, request)
    }

private fun ReplyAdmissionService.debugSnapshot(): ReplyAdmissionDebugSnapshot =
    runBlocking {
        debugSnapshotSuspend()
    }
