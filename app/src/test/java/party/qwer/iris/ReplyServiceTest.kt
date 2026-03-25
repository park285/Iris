package party.qwer.iris

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ReplyServiceTest {
    private val testConfig =
        object : ConfigProvider {
            override val botId = 0L
            override val botName = ""
            override val botSocketPort = 0
            override val botToken = ""
            override val webhookToken = ""
            override val dbPollingRate = 1000L
            override val messageSendRate = 0L

            override fun webhookEndpointFor(route: String) = ""
        }

    @Test
    fun `ReplyQueueKey distinguishes by chatId and threadId`() {
        val key1 = ReplyQueueKey(chatId = 1L, threadId = 100L)
        val key2 = ReplyQueueKey(chatId = 1L, threadId = 100L)
        val key3 = ReplyQueueKey(chatId = 1L, threadId = 200L)
        val key4 = ReplyQueueKey(chatId = 1L, threadId = null)

        assertEquals(key1, key2)
        assertNotEquals(key1, key3)
        assertNotEquals(key1, key4)
        assertNotEquals(key3, key4)
    }

    @Test
    fun `rejects enqueue when max workers exceeded`() {
        val service = ReplyService(testConfig)
        service.start()

        for (i in 0L until 16L) {
            val result = service.sendMessage("ref", chatId = i, msg = "test", threadId = null, threadScope = null)
            assertEquals(ReplyAdmissionStatus.ACCEPTED, result.status, "worker $i should be accepted")
        }

        val overflow = service.sendMessage("ref", chatId = 99L, msg = "test", threadId = null, threadScope = null)
        assertEquals(ReplyAdmissionStatus.QUEUE_FULL, overflow.status)

        service.shutdown()
    }

    @Test
    fun `rejects enqueue after shutdown`() {
        val service = ReplyService(testConfig)
        service.start()
        service.shutdown()

        val result = service.sendMessage("ref", chatId = 1L, msg = "test", threadId = null, threadScope = null)
        assertEquals(ReplyAdmissionStatus.SHUTDOWN, result.status)
    }

    @Test
    fun `rejects enqueue when per-worker queue is full`() {
        val slowConfig =
            object : ConfigProvider {
                override val botId = 0L
                override val botName = ""
                override val botSocketPort = 0
                override val botToken = ""
                override val webhookToken = ""
                override val dbPollingRate = 1000L
                override val messageSendRate = 60_000L

                override fun webhookEndpointFor(route: String) = ""
            }
        val service = ReplyService(slowConfig)
        service.start()

        var fullCount = 0
        for (i in 0 until 20) {
            val result = service.sendMessage("ref", chatId = 1L, msg = "msg$i", threadId = null, threadScope = null)
            if (result.status == ReplyAdmissionStatus.QUEUE_FULL) fullCount++
        }

        assertTrue(fullCount > 0, "at least one enqueue should be rejected as QUEUE_FULL")
        service.shutdown()
    }

    @Test
    fun `shutdown then enqueue returns SHUTDOWN`() {
        val service = ReplyService(testConfig)
        service.start()

        service.sendMessage("ref", chatId = 1L, msg = "drain", threadId = null, threadScope = null)
        service.shutdown()

        val result = service.sendMessage("ref", chatId = 1L, msg = "after", threadId = null, threadScope = null)
        assertEquals(ReplyAdmissionStatus.SHUTDOWN, result.status)
    }

    @Test
    fun `same key messages are all accepted in order`() {
        val service = ReplyService(testConfig)
        service.start()

        val results =
            (1..10).map { i ->
                service.sendMessage("ref", chatId = 1L, msg = "msg$i", threadId = 100L, threadScope = 1)
            }

        results.forEach { assertEquals(ReplyAdmissionStatus.ACCEPTED, it.status) }
        service.shutdown()
    }

    @Test
    fun `different keys create independent workers`() {
        val service = ReplyService(testConfig)
        service.start()

        val result1 = service.sendMessage("ref", chatId = 1L, msg = "a", threadId = 100L, threadScope = 1)
        val result2 = service.sendMessage("ref", chatId = 1L, msg = "b", threadId = 200L, threadScope = 1)
        val result3 = service.sendMessage("ref", chatId = 2L, msg = "c", threadId = null, threadScope = null)

        assertEquals(ReplyAdmissionStatus.ACCEPTED, result1.status)
        assertEquals(ReplyAdmissionStatus.ACCEPTED, result2.status)
        assertEquals(ReplyAdmissionStatus.ACCEPTED, result3.status)

        service.shutdown()
    }

    @Test
    fun `new worker is created after restart for same key`() {
        val service = ReplyService(testConfig)
        service.start()

        val result1 = service.sendMessage("ref", chatId = 1L, msg = "before", threadId = null, threadScope = null)
        assertEquals(ReplyAdmissionStatus.ACCEPTED, result1.status)

        service.restart()

        val result2 = service.sendMessage("ref", chatId = 1L, msg = "after", threadId = null, threadScope = null)
        assertEquals(ReplyAdmissionStatus.ACCEPTED, result2.status)

        service.shutdown()
    }

    @Test
    fun `same key preserves send order`() {
        val service = ReplyService(testConfig)
        service.start()
        val executed = CopyOnWriteArrayList<Int>()
        val latch = CountDownLatch(5)

        for (i in 0 until 5) {
            service.enqueueAction(chatId = 1L, threadId = 100L) {
                executed.add(i)
                latch.countDown()
            }
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS), "all messages should be processed")
        assertEquals(listOf(0, 1, 2, 3, 4), executed)
        service.shutdown()
    }

    @Test
    fun `send mutex prevents concurrent send calls`() {
        val service = ReplyService(testConfig)
        service.start()
        val concurrent = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)
        val latch = CountDownLatch(4)

        for (i in 0L until 2L) {
            repeat(2) {
                service.enqueueAction(chatId = i, threadId = null) {
                    val cur = concurrent.incrementAndGet()
                    maxConcurrent.updateAndGet { prev -> maxOf(prev, cur) }
                    delay(50)
                    concurrent.decrementAndGet()
                    latch.countDown()
                }
            }
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS), "all sends should complete")
        assertEquals(1, maxConcurrent.get(), "send calls must not overlap (mutex)")
        service.shutdown()
    }

    @Test
    fun `shutdown drains pending messages`() {
        val service = ReplyService(testConfig)
        service.start()
        val executed = AtomicInteger(0)

        repeat(3) {
            service.enqueueAction(chatId = 1L, threadId = null) {
                delay(20)
                executed.incrementAndGet()
            }
        }

        service.shutdown()
        assertEquals(3, executed.get(), "all pending messages should be drained before shutdown completes")
    }

    @Test
    fun `different keys process independently`() {
        val slowConfig =
            object : ConfigProvider {
                override val botId = 0L
                override val botName = ""
                override val botSocketPort = 0
                override val botToken = ""
                override val webhookToken = ""
                override val dbPollingRate = 1000L
                override val messageSendRate = 200L
                override fun webhookEndpointFor(route: String) = ""
            }
        val service = ReplyService(slowConfig)
        service.start()
        val latch = CountDownLatch(2)

        val startTime = System.currentTimeMillis()
        for (i in 0L until 2L) {
            service.enqueueAction(chatId = i, threadId = null) {
                latch.countDown()
            }
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS), "both workers should complete")
        val elapsed = System.currentTimeMillis() - startTime
        // 직렬이면 최소 200ms+ (첫 번째 워커의 rate limit delay), 병렬이면 거의 즉시
        assertTrue(elapsed < 150, "different key workers should not wait for each other's rate limit (took ${elapsed}ms)")
        service.shutdown()
    }
}
