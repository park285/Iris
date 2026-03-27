package party.qwer.iris

import android.content.Intent
import kotlinx.coroutines.delay
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReplyServiceTest {
    private companion object {
        private const val VALID_TEST_PNG_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+cq1cAAAAASUVORK5CYII="
    }

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
    fun `same lane mutex prevents concurrent send calls`() {
        val service = ReplyService(testConfig)
        service.start()
        val concurrent = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)
        val latch = CountDownLatch(4)

        for (i in 0L until 2L) {
            repeat(2) {
                service.enqueueAction(chatId = i, threadId = null, lane = ReplySendLane.TEXT) {
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
    fun `different lanes can execute concurrently`() {
        val service = ReplyService(testConfig)
        service.start()
        val concurrent = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)
        val latch = CountDownLatch(2)

        service.enqueueAction(chatId = 1L, threadId = null, lane = ReplySendLane.TEXT) {
            val cur = concurrent.incrementAndGet()
            maxConcurrent.updateAndGet { prev -> maxOf(prev, cur) }
            delay(50)
            concurrent.decrementAndGet()
            latch.countDown()
        }
        service.enqueueAction(chatId = 2L, threadId = null, lane = ReplySendLane.NATIVE_IMAGE) {
            val cur = concurrent.incrementAndGet()
            maxConcurrent.updateAndGet { prev -> maxOf(prev, cur) }
            delay(50)
            concurrent.decrementAndGet()
            latch.countDown()
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS), "different lanes should complete")
        assertTrue(maxConcurrent.get() >= 2, "different lanes should be able to overlap")
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

    @Test
    fun `buildImageShareIntentSpec adds session-chain extras for threaded image replies`() {
        val spec =
            buildImageShareIntentSpec(
                room = 18478615493603057L,
                threadId = 3804154209723703299L,
                threadScope = 2,
                sessionId = "session-123",
                createdAt = 1_742_890_000_000L,
            )

        assertEquals(Intent.ACTION_SEND_MULTIPLE, spec.action)
        assertEquals("com.kakao.talk", spec.packageName)
        assertEquals("image/*", spec.mimeType)
        assertEquals("iris:session-123", spec.identifier)
        assertEquals("session-123", spec.sessionId)
        assertEquals("3804154209723703299", spec.threadId)
        assertEquals(2, spec.threadScope)
        assertEquals("18478615493603057", spec.roomId)
        assertEquals(1_742_890_000_000L, spec.createdAt)
        assertEquals(1, spec.keyType)
        assertTrue(spec.fromDirectShare)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP, spec.flags)
    }

    @Test
    fun `buildImageShareIntentSpec omits thread extras when no thread metadata exists`() {
        val spec =
            buildImageShareIntentSpec(
                room = 18478615493603057L,
                threadId = null,
                threadScope = null,
                sessionId = "session-456",
                createdAt = 1_742_890_000_001L,
            )

        assertEquals("iris:session-456", spec.identifier)
        assertEquals("session-456", spec.sessionId)
        assertEquals("18478615493603057", spec.roomId)
        assertNull(spec.threadId)
        assertNull(spec.threadScope)
        assertFalse(spec.hasThreadMetadata)
    }

    @Test
    fun `sendNativePhoto rejects invalid payload before native bootstrap`() {
        val service = ReplyService(testConfig)
        service.start()

        val result =
            service.sendNativePhoto(
                room = 18478615493603057L,
                base64ImageDataString = "not-base64",
                threadId = 3804154209723703299L,
                threadScope = 2,
            )

        assertEquals(ReplyAdmissionStatus.INVALID_PAYLOAD, result.status)
        service.shutdown()
    }

    @Test
    fun `sendNativePhoto rejects payload with invalid trailing base64 data`() {
        val service = ReplyService(testConfig)
        service.start()
        val payload = "A".repeat(256) + "A==="

        val result =
            service.sendNativePhoto(
                room = 18478615493603057L,
                base64ImageDataString = payload,
                threadId = 3804154209723703299L,
                threadScope = 2,
            )

        assertEquals(ReplyAdmissionStatus.INVALID_PAYLOAD, result.status)
        service.shutdown()
    }

    @Test
    fun `request id status advances through queue and send`() {
        val service = ReplyService(testConfig)
        service.start()
        val requestId = "req-123"
        val latch = CountDownLatch(1)

        val result =
            service.enqueueAction(
                chatId = 1L,
                threadId = null,
                lane = ReplySendLane.TEXT,
                requestId = requestId,
            ) {
                latch.countDown()
            }

        assertEquals(ReplyAdmissionStatus.ACCEPTED, result.status)
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        var finalState = service.replyStatusOrNull(requestId)?.state
        repeat(20) {
            if (finalState == "handoff_completed") {
                return@repeat
            }
            Thread.sleep(50)
            finalState = service.replyStatusOrNull(requestId)?.state
        }
        assertEquals("handoff_completed", finalState)
        service.shutdown()
    }
}
