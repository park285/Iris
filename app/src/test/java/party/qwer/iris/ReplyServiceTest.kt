package party.qwer.iris

import kotlinx.coroutines.delay
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
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
            override val messageSendJitterMax = 0L

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
                override val messageSendJitterMax = 0L

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
                override val messageSendJitterMax = 0L

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
    fun `sendNativeMultiplePhotos rejects when image count exceeds limit`() {
        val service = ReplyService(testConfig)
        service.start()

        val result =
            service.sendNativeMultiplePhotos(
                room = 18478615493603057L,
                base64ImageDataStrings = List(9) { VALID_TEST_PNG_BASE64 },
                threadId = null,
                threadScope = null,
            )

        assertEquals(ReplyAdmissionStatus.INVALID_PAYLOAD, result.status)
        service.shutdown()
    }

    @Test
    fun `sendNativePhoto decodes payload only once`() {
        val decodeCalls = AtomicInteger(0)
        val latch = CountDownLatch(1)
        val imageDir = Files.createTempDirectory("iris-reply-service").toFile()
        val service =
            ReplyService(
                testConfig,
                nativeImageReplySender =
                    object : NativeImageReplySender {
                        override fun send(
                            roomId: Long,
                            imagePaths: List<String>,
                            threadId: Long?,
                            threadScope: Int?,
                            requestId: String?,
                        ) {
                            latch.countDown()
                        }
                    },
                imageDecoder = { payload ->
                    decodeCalls.incrementAndGet()
                    Base64.getMimeDecoder().decode(payload)
                },
                mediaScanner = {},
                imageDir = imageDir,
            )
        service.start()

        val result =
            service.sendNativePhoto(
                room = 18478615493603057L,
                base64ImageDataString = VALID_TEST_PNG_BASE64,
                threadId = null,
                threadScope = null,
            )

        assertEquals(ReplyAdmissionStatus.ACCEPTED, result.status)
        assertEquals(1, decodeCalls.get(), "validation should decode payload exactly once")
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        service.shutdown()
        imageDir.deleteRecursively()
    }

    @Test
    fun `sendNativePhoto cleans up prepared files after successful native send`() {
        val latch = CountDownLatch(1)
        val imageDir = Files.createTempDirectory("iris-reply-cleanup").toFile()
        val observedPaths = CopyOnWriteArrayList<String>()
        val service =
            ReplyService(
                testConfig,
                nativeImageReplySender =
                    object : NativeImageReplySender {
                        override fun send(
                            roomId: Long,
                            imagePaths: List<String>,
                            threadId: Long?,
                            threadScope: Int?,
                            requestId: String?,
                        ) {
                            observedPaths += imagePaths
                            latch.countDown()
                        }
                    },
                mediaScanner = {},
                imageDir = imageDir,
            )
        service.start()

        val result =
            service.sendNativePhoto(
                room = 18478615493603057L,
                base64ImageDataString = VALID_TEST_PNG_BASE64,
                threadId = null,
                threadScope = null,
            )

        assertEquals(ReplyAdmissionStatus.ACCEPTED, result.status)
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        var cleaned = false
        repeat(20) {
            if (observedPaths.isNotEmpty() && observedPaths.all { path -> !File(path).exists() }) {
                cleaned = true
                return@repeat
            }
            Thread.sleep(50)
        }
        assertTrue(observedPaths.isNotEmpty(), "native sender should observe prepared image paths")
        assertTrue(cleaned, "prepared files should be cleaned after native send")
        service.shutdown()
        imageDir.deleteRecursively()
    }

    @Test
    fun `validateImagePayloads rejects when decoded total exceeds limit`() {
        val payloads = listOf("a", "b", "c")

        assertFailsWith<IllegalArgumentException> {
            validateImagePayloads(
                payloads,
                imageDecoder = { ByteArray(4) },
                maxImagesPerRequest = 8,
                maxTotalBytes = 10,
            )
        }
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

    @Test
    fun `threaded text replies use share graft lane instead of notification reply lane`() {
        val notificationStarts = AtomicInteger(0)
        val shareStarts = AtomicInteger(0)
        val latch = CountDownLatch(1)
        val service =
            ReplyService(
                testConfig,
                notificationReplySender = { _, _, _, _, _ ->
                    notificationStarts.incrementAndGet()
                },
                sharedTextReplySender = { _, _, _, _ ->
                    shareStarts.incrementAndGet()
                    latch.countDown()
                },
            )
        service.start()

        val result =
            service.sendMessage(
                referer = "ref",
                chatId = 18478615493603057L,
                msg = "**thread text**",
                threadId = 3805486995143352321L,
                threadScope = 2,
            )

        assertEquals(ReplyAdmissionStatus.ACCEPTED, result.status)
        assertTrue(latch.await(5, TimeUnit.SECONDS), "threaded text should use share/graft lane")
        assertEquals(0, notificationStarts.get())
        assertEquals(1, shareStarts.get())
        service.shutdown()
    }

    @Test
    fun `room text replies keep notification reply lane`() {
        val notificationStarts = AtomicInteger(0)
        val shareStarts = AtomicInteger(0)
        val latch = CountDownLatch(1)
        val service =
            ReplyService(
                testConfig,
                notificationReplySender = { _, _, _, _, _ ->
                    notificationStarts.incrementAndGet()
                    latch.countDown()
                },
                sharedTextReplySender = { _, _, _, _ ->
                    shareStarts.incrementAndGet()
                },
            )
        service.start()

        val result =
            service.sendMessage(
                referer = "ref",
                chatId = 18478615493603057L,
                msg = "plain room text",
                threadId = null,
                threadScope = null,
            )

        assertEquals(ReplyAdmissionStatus.ACCEPTED, result.status)
        assertTrue(latch.await(5, TimeUnit.SECONDS), "room text should keep notification reply lane")
        assertEquals(1, notificationStarts.get())
        assertEquals(0, shareStarts.get())
        service.shutdown()
    }

    @Test
    fun `threaded text replies default share graft scope to two when omitted`() {
        val capturedScope = AtomicInteger(-1)
        val latch = CountDownLatch(1)
        val service =
            ReplyService(
                testConfig,
                sharedTextReplySender = { _, _, _, threadScope ->
                    capturedScope.set(threadScope ?: -1)
                    latch.countDown()
                },
            )
        service.start()

        val result =
            service.sendMessage(
                referer = "ref",
                chatId = 18478615493603057L,
                msg = "thread scope default",
                threadId = 3805486995143352321L,
                threadScope = null,
            )

        assertEquals(ReplyAdmissionStatus.ACCEPTED, result.status)
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals(2, capturedScope.get())
        service.shutdown()
    }

    @Test
    fun `reply markdown alias uses shared text reply lane`() {
        val shareStarts = AtomicInteger(0)
        val capturedScope = AtomicInteger(-1)
        val latch = CountDownLatch(1)
        val service =
            ReplyService(
                testConfig,
                sharedTextReplySender = { _, _, _, threadScope ->
                    shareStarts.incrementAndGet()
                    capturedScope.set(threadScope ?: -1)
                    latch.countDown()
                },
            )
        service.start()

        val result =
            service.sendReplyMarkdown(
                room = 18478615493603057L,
                msg = "**markdown alias**",
                threadId = 3805486995143352321L,
                threadScope = null,
            )

        assertEquals(ReplyAdmissionStatus.ACCEPTED, result.status)
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals(1, shareStarts.get())
        assertEquals(2, capturedScope.get())
        service.shutdown()
    }
}
