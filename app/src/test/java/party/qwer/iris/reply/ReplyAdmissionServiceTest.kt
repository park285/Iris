package party.qwer.iris.reply

import party.qwer.iris.ReplyAdmissionStatus
import party.qwer.iris.ReplyQueueKey
import kotlin.test.Test
import kotlin.test.assertEquals

class ReplyAdmissionServiceTest {
    @Test
    fun `accepts request when started and worker available`() {
        val service = ReplyAdmissionService(
            maxWorkers = 16,
            perWorkerQueueCapacity = 16,
        )
        service.start()

        val result = service.enqueue(
            key = ReplyQueueKey(chatId = 1L, threadId = null),
            request = stubPipelineRequest(),
        )

        assertEquals(ReplyAdmissionStatus.ACCEPTED, result.status)
        service.shutdown()
    }

    @Test
    fun `rejects when not started`() {
        val service = ReplyAdmissionService(
            maxWorkers = 16,
            perWorkerQueueCapacity = 16,
        )

        val result = service.enqueue(
            key = ReplyQueueKey(chatId = 1L, threadId = null),
            request = stubPipelineRequest(),
        )

        assertEquals(ReplyAdmissionStatus.SHUTDOWN, result.status)
    }

    @Test
    fun `rejects after shutdown`() {
        val service = ReplyAdmissionService(
            maxWorkers = 16,
            perWorkerQueueCapacity = 16,
        )
        service.start()
        service.shutdown()

        val result = service.enqueue(
            key = ReplyQueueKey(chatId = 1L, threadId = null),
            request = stubPipelineRequest(),
        )

        assertEquals(ReplyAdmissionStatus.SHUTDOWN, result.status)
    }

    @Test
    fun `rejects when max workers exceeded`() {
        val service = ReplyAdmissionService(
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
        val service = ReplyAdmissionService(
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
        val service = ReplyAdmissionService(
            maxWorkers = 16,
            perWorkerQueueCapacity = 1,
            workerIdleTimeoutMs = 60_000L,
        )
        service.start()

        val key = ReplyQueueKey(chatId = 1L, threadId = null)
        val blockingRequest = stubPipelineRequest(blockMs = 60_000L)
        val r1 = service.enqueue(key, blockingRequest)
        assertEquals(ReplyAdmissionStatus.ACCEPTED, r1.status)

        Thread.sleep(50)
        val r2 = service.enqueue(key, stubPipelineRequest())
        assertEquals(ReplyAdmissionStatus.ACCEPTED, r2.status)

        val r3 = service.enqueue(key, stubPipelineRequest())
        assertEquals(ReplyAdmissionStatus.QUEUE_FULL, r3.status)
        service.shutdown()
    }

    @Test
    fun `restart clears workers and accepts new requests`() {
        val service = ReplyAdmissionService(
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
        val service = ReplyAdmissionService(
            maxWorkers = 16,
            perWorkerQueueCapacity = 16,
            workerIdleTimeoutMs = 50L,
        )
        service.start()

        val key = ReplyQueueKey(chatId = 1L, threadId = null)
        val r1 = service.enqueue(key, stubPipelineRequest())
        assertEquals(ReplyAdmissionStatus.ACCEPTED, r1.status)

        Thread.sleep(200)

        val r2 = service.enqueue(key, stubPipelineRequest())
        assertEquals(ReplyAdmissionStatus.ACCEPTED, r2.status)
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
}
