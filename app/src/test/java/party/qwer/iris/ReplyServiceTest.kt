package party.qwer.iris

import kotlinx.coroutines.delay
import party.qwer.iris.model.ReplyLifecycleState
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
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
            override val inboundSigningSecret = ""
            override val outboundWebhookToken = ""
            override val botControlToken = ""
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
                override val inboundSigningSecret = ""
                override val outboundWebhookToken = ""
                override val botControlToken = ""
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
    fun `global gate paces send start times across workers`() {
        val pacingConfig =
            object : ConfigProvider {
                override val botId = 0L
                override val botName = ""
                override val botSocketPort = 0
                override val inboundSigningSecret = ""
                override val outboundWebhookToken = ""
                override val botControlToken = ""
                override val dbPollingRate = 1000L
                override val messageSendRate = 100L
                override val messageSendJitterMax = 0L

                override fun webhookEndpointFor(route: String) = ""
            }
        val service = ReplyService(pacingConfig)
        service.start()
        val timestamps = CopyOnWriteArrayList<Long>()
        val latch = CountDownLatch(4)

        for (i in 0L until 2L) {
            repeat(2) {
                service.enqueueAction(chatId = i, threadId = null, lane = ReplySendLane.TEXT) {
                    timestamps.add(System.currentTimeMillis())
                    delay(50)
                    latch.countDown()
                }
            }
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS), "all sends should complete")
        assertEquals(4, timestamps.size)
        val sorted = timestamps.sorted()
        for (i in 1 until sorted.size) {
            val gap = sorted[i] - sorted[i - 1]
            assertTrue(gap >= 80, "send starts should be paced by at least ~100ms (gap was ${gap}ms)")
        }
        service.shutdown()
    }

    @Test
    fun `global gate paces send start times across all lanes`() {
        val pacingConfig =
            object : ConfigProvider {
                override val botId = 0L
                override val botName = ""
                override val botSocketPort = 0
                override val inboundSigningSecret = ""
                override val outboundWebhookToken = ""
                override val botControlToken = ""
                override val dbPollingRate = 1000L
                override val messageSendRate = 100L
                override val messageSendJitterMax = 0L

                override fun webhookEndpointFor(route: String) = ""
            }
        val service = ReplyService(pacingConfig)
        service.start()
        val timestamps = CopyOnWriteArrayList<Long>()
        val latch = CountDownLatch(2)

        service.enqueueAction(chatId = 1L, threadId = null, lane = ReplySendLane.TEXT) {
            timestamps.add(System.currentTimeMillis())
            delay(50)
            latch.countDown()
        }
        service.enqueueAction(chatId = 2L, threadId = null, lane = ReplySendLane.NATIVE_IMAGE) {
            timestamps.add(System.currentTimeMillis())
            delay(50)
            latch.countDown()
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS), "different lanes should complete")
        assertEquals(2, timestamps.size)
        val sorted = timestamps.sorted()
        val gap = sorted[1] - sorted[0]
        assertTrue(gap >= 80, "all lanes should respect shared pacing (gap was ${gap}ms)")
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
    fun `global gate enforces pacing between sends from different workers`() {
        val pacingConfig =
            object : ConfigProvider {
                override val botId = 0L
                override val botName = ""
                override val botSocketPort = 0
                override val inboundSigningSecret = ""
                override val outboundWebhookToken = ""
                override val botControlToken = ""
                override val dbPollingRate = 1000L
                override val messageSendRate = 100L
                override val messageSendJitterMax = 0L

                override fun webhookEndpointFor(route: String) = ""
            }
        val service = ReplyService(pacingConfig)
        service.start()
        val timestamps = CopyOnWriteArrayList<Long>()
        val latch = CountDownLatch(3)

        for (i in 0L until 3L) {
            service.enqueueAction(chatId = i, threadId = null) {
                timestamps.add(System.currentTimeMillis())
                latch.countDown()
            }
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS), "all sends should complete")
        assertEquals(3, timestamps.size)
        val sorted = timestamps.sorted()
        for (i in 1 until sorted.size) {
            val gap = sorted[i] - sorted[i - 1]
            assertTrue(gap >= 80, "sends should be paced by at least ~100ms (gap was ${gap}ms)")
        }
        service.shutdown()
    }

    @Test
    fun `shutdown terminates workers blocked in dispatch gate pacing`() {
        val pacingConfig =
            object : ConfigProvider {
                override val botId = 0L
                override val botName = ""
                override val botSocketPort = 0
                override val inboundSigningSecret = ""
                override val outboundWebhookToken = ""
                override val botControlToken = ""
                override val dbPollingRate = 1000L
                override val messageSendRate = 5_000L
                override val messageSendJitterMax = 0L

                override fun webhookEndpointFor(route: String) = ""
            }
        val service = ReplyService(pacingConfig)
        service.start()
        val firstDone = CountDownLatch(1)

        service.enqueueAction(chatId = 1L, threadId = null) {
            firstDone.countDown()
        }
        assertTrue(firstDone.await(5, TimeUnit.SECONDS), "first send should complete")

        service.enqueueAction(chatId = 1L, threadId = null) {}
        Thread.sleep(100)

        val start = System.currentTimeMillis()
        service.shutdown()
        val elapsed = System.currentTimeMillis() - start

        assertTrue(elapsed < 15_000, "shutdown should complete by cancelling gate-blocked workers (took ${elapsed}ms)")
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
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals(1, decodeCalls.get(), "worker should decode payload exactly once")
        service.shutdown()
        imageDir.deleteRecursively()
    }

    @Test
    fun `sendNativePhoto admission does not wait for image decode`() {
        val decodeStarted = CountDownLatch(1)
        val allowDecode = CountDownLatch(1)
        val sendCompleted = CountDownLatch(1)
        val resultRef = AtomicReference<ReplyAdmissionResult?>()
        val imageDir = Files.createTempDirectory("iris-reply-deferred-decode").toFile()
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
                            sendCompleted.countDown()
                        }
                    },
                imageDecoder = { payload ->
                    decodeStarted.countDown()
                    assertTrue(allowDecode.await(5, TimeUnit.SECONDS), "worker decode should be releasable")
                    Base64.getMimeDecoder().decode(payload)
                },
                mediaScanner = {},
                imageDir = imageDir,
            )
        service.start()

        val admissionReturned = CountDownLatch(1)
        val caller =
            Thread {
                resultRef.set(
                    service.sendNativePhoto(
                        room = 18478615493603057L,
                        base64ImageDataString = VALID_TEST_PNG_BASE64,
                        threadId = null,
                        threadScope = null,
                    ),
                )
                admissionReturned.countDown()
            }
        caller.start()

        assertTrue(
            admissionReturned.await(500, TimeUnit.MILLISECONDS),
            "queue admission should not block on image decode",
        )
        assertEquals(ReplyAdmissionStatus.ACCEPTED, resultRef.get()?.status)
        assertTrue(decodeStarted.await(5, TimeUnit.SECONDS), "worker should eventually start image decode")
        allowDecode.countDown()
        assertTrue(sendCompleted.await(5, TimeUnit.SECONDS), "native send should complete after worker decode")

        service.shutdown()
        caller.join(5_000)
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
            if (finalState == ReplyLifecycleState.HANDOFF_COMPLETED) {
                return@repeat
            }
            Thread.sleep(50)
            finalState = service.replyStatusOrNull(requestId)?.state
        }
        assertEquals(ReplyLifecycleState.HANDOFF_COMPLETED, finalState)
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
