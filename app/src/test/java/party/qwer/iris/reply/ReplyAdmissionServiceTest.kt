package party.qwer.iris.reply

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import party.qwer.iris.ReplyAdmissionStatus
import party.qwer.iris.ReplyQueueKey
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
        val service =
            ReplyAdmissionService(
                maxWorkers = 16,
                perWorkerQueueCapacity = 1,
                workerIdleTimeoutMs = 60_000L,
            )
        service.start()

        val key = ReplyQueueKey(chatId = 1L, threadId = null)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blockingRequest = controlledPipelineRequest(started, release)
        val r1 = service.enqueue(key, blockingRequest)
        assertEquals(ReplyAdmissionStatus.ACCEPTED, r1.status)

        assertTrue(started.await(1, TimeUnit.SECONDS), "expected worker to start processing")
        waitUntil("expected one active worker") { service.debugSnapshot().activeWorkers == 1 }
        val r2 = service.enqueue(key, stubPipelineRequest())
        assertEquals(ReplyAdmissionStatus.ACCEPTED, r2.status)

        val r3 = service.enqueue(key, stubPipelineRequest())
        assertEquals(ReplyAdmissionStatus.QUEUE_FULL, r3.status)
        release.countDown()
        service.shutdown()
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
        val service =
            ReplyAdmissionService(
                maxWorkers = 16,
                perWorkerQueueCapacity = 16,
                workerIdleTimeoutMs = 50L,
            )
        service.start()

        val key = ReplyQueueKey(chatId = 1L, threadId = null)
        val r1 = service.enqueue(key, stubPipelineRequest())
        assertEquals(ReplyAdmissionStatus.ACCEPTED, r1.status)

        waitUntil("expected idle worker cleanup") { service.debugSnapshot().activeWorkers == 0 }

        val r2 = service.enqueue(key, stubPipelineRequest())
        assertEquals(ReplyAdmissionStatus.ACCEPTED, r2.status)
        service.shutdown()
    }

    @Test
    fun `idle worker release frees worker slot for a different key`() {
        val service =
            ReplyAdmissionService(
                maxWorkers = 1,
                perWorkerQueueCapacity = 16,
                workerIdleTimeoutMs = 50L,
            )
        service.start()

        val firstKey = ReplyQueueKey(chatId = 1L, threadId = null)
        val secondKey = ReplyQueueKey(chatId = 2L, threadId = null)

        assertEquals(ReplyAdmissionStatus.ACCEPTED, service.enqueue(firstKey, stubPipelineRequest()).status)
        waitUntil("expected idle worker cleanup") { service.debugSnapshot().activeWorkers == 0 }

        assertEquals(ReplyAdmissionStatus.ACCEPTED, service.enqueue(secondKey, stubPipelineRequest()).status)
        assertEquals(1, service.debugSnapshot().activeWorkers)
        service.shutdown()
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
        started: CountDownLatch,
        release: CountDownLatch,
    ): PipelineRequest =
        object : PipelineRequest {
            override val requestId: String? = null

            override suspend fun prepare() {
                started.countDown()
                release.await(1, TimeUnit.SECONDS)
            }

            override suspend fun send() {}
        }

    private fun waitUntil(
        message: String,
        condition: () -> Boolean,
    ) = runBlocking {
        var satisfied = condition()
        repeat(100) {
            if (satisfied) return@repeat
            delay(5L)
            satisfied = condition()
        }
        assertTrue(satisfied, message)
    }
}
