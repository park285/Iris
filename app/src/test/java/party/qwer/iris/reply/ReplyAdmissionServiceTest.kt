package party.qwer.iris.reply

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import party.qwer.iris.ReplyAdmissionStatus
import party.qwer.iris.ReplyQueueKey
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class ReplyAdmissionServiceTest {
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
                request = stubPipelineRequest(),
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
                request = stubPipelineRequest(),
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
                request = stubPipelineRequest(),
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

        val r1 = service.enqueue(ReplyQueueKey(chatId = 1L, threadId = null), stubPipelineRequest())
        val r2 = service.enqueue(ReplyQueueKey(chatId = 2L, threadId = null), stubPipelineRequest())
        val r3 = service.enqueue(ReplyQueueKey(chatId = 3L, threadId = null), stubPipelineRequest())

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

        val r1 = service.enqueue(ReplyQueueKey(chatId = 1L, threadId = null), stubPipelineRequest())
        val r2 = service.enqueue(ReplyQueueKey(chatId = 1L, threadId = null), stubPipelineRequest())

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
            val blockingRequest = controlledPipelineRequest(started, release)
            val r1 = service.enqueueSuspend(key, blockingRequest)
            assertEquals(ReplyAdmissionStatus.ACCEPTED, r1.status)

            advanceUntilIdle()
            started.await()
            assertEquals(1, service.debugSnapshotSuspend().activeWorkers)

            val r2 = service.enqueueSuspend(key, stubPipelineRequest())
            assertEquals(ReplyAdmissionStatus.ACCEPTED, r2.status)

            val r3 = service.enqueueSuspend(key, stubPipelineRequest())
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

        val r1 = service.enqueue(ReplyQueueKey(chatId = 1L, threadId = null), stubPipelineRequest())
        assertEquals(ReplyAdmissionStatus.ACCEPTED, r1.status)

        service.restart()

        val r2 = service.enqueue(ReplyQueueKey(chatId = 1L, threadId = null), stubPipelineRequest())
        assertEquals(ReplyAdmissionStatus.ACCEPTED, r2.status)
        service.shutdown()
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
            val r1 = service.enqueueSuspend(key, stubPipelineRequest())
            assertEquals(ReplyAdmissionStatus.ACCEPTED, r1.status)

            advanceUntilIdle()
            advanceTimeBy(50L)
            advanceUntilIdle()
            assertEquals(0, service.debugSnapshotSuspend().activeWorkers)

            val r2 = service.enqueueSuspend(key, stubPipelineRequest())
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

            assertEquals(ReplyAdmissionStatus.ACCEPTED, service.enqueueSuspend(firstKey, stubPipelineRequest()).status)
            advanceUntilIdle()
            advanceTimeBy(50L)
            advanceUntilIdle()
            assertEquals(0, service.debugSnapshotSuspend().activeWorkers)

            assertEquals(ReplyAdmissionStatus.ACCEPTED, service.enqueueSuspend(secondKey, stubPipelineRequest()).status)
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
            service.enqueue(ReplyQueueKey(chatId = 1L, threadId = null), stubPipelineRequest()).status,
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
                    request = stubPipelineRequest(),
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

    private fun stubPipelineRequest(blockMs: Long = 0L): PipelineRequest =
        object : PipelineRequest {
            override val requestId: String? = null

            override suspend fun prepare() {
                if (blockMs > 0L) {
                    kotlinx.coroutines.delay(blockMs)
                }
            }

            override suspend fun send() {}
        }

    private fun controlledPipelineRequest(
        started: CompletableDeferred<Unit>,
        release: CompletableDeferred<Unit>,
    ): PipelineRequest =
        object : PipelineRequest {
            override val requestId: String? = null

            override suspend fun prepare() {
                started.complete(Unit)
                release.await()
            }

            override suspend fun send() {}
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
    request: PipelineRequest,
): party.qwer.iris.ReplyAdmissionResult =
    runBlocking {
        enqueueSuspend(key, request)
    }

private fun ReplyAdmissionService.debugSnapshot(): ReplyAdmissionDebugSnapshot =
    runBlocking {
        debugSnapshotSuspend()
    }
