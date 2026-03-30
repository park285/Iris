@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package party.qwer.iris

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import party.qwer.iris.model.ReplyLifecycleState
import party.qwer.iris.reply.ReplyThreadId
import party.qwer.iris.storage.ChatId
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ReplyServiceTest {
    private companion object {
        private val VALID_TEST_PNG_BYTES =
            byteArrayOf(
                0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            )
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

    private fun deterministicReplyService(
        config: ConfigProvider = testConfig,
        scheduler: TestCoroutineScheduler,
        dispatcher: CoroutineDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(scheduler),
    ): ReplyService =
        ReplyService(
            config = config,
            admissionDispatcher = dispatcher,
            dispatchClock = { scheduler.currentTime },
            statusTickerNanos = { scheduler.currentTime * 1_000_000L },
            statusUpdatedAtEpochMs = { scheduler.currentTime },
        )

    @Test
    fun `ReplyQueueKey distinguishes by chatId and threadId`() {
        val key1 = ReplyQueueKey(chatId = 1L, threadId = 100L)
        val key2 = ReplyQueueKey(chatId = 1L, threadId = 100L)
        val key3 = ReplyQueueKey(chatId = 1L, threadId = 200L)
        val key4 = ReplyQueueKey(chatId = 1L, threadId = null)

        assertTrue(key1.chatId == ChatId(1L))
        assertTrue(key1.threadId == ReplyThreadId(100L))
        assertTrue(key4.chatId == ChatId(1L))
        assertEquals(null, key4.threadId)
        assertEquals(key1, key2)
        assertNotEquals(key1, key3)
        assertNotEquals(key1, key4)
        assertNotEquals(key3, key4)
    }

    @Test
    fun `rejects enqueue when max workers exceeded`() {
        val service = ReplyService(testConfig)
        service.startBlocking()

        for (i in 0L until 16L) {
            val result = service.sendMessageBlocking("ref", chatId = i, msg = "test", threadId = null, threadScope = null)
            assertEquals(ReplyAdmissionStatus.ACCEPTED, result.status, "worker $i should be accepted")
        }

        val overflow = service.sendMessageBlocking("ref", chatId = 99L, msg = "test", threadId = null, threadScope = null)
        assertEquals(ReplyAdmissionStatus.QUEUE_FULL, overflow.status)

        service.shutdownBlocking()
    }

    @Test
    fun `rejects enqueue after shutdown`() {
        val service = ReplyService(testConfig)
        service.startBlocking()
        service.shutdownBlocking()

        val result = service.sendMessageBlocking("ref", chatId = 1L, msg = "test", threadId = null, threadScope = null)
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
        service.startBlocking()

        var fullCount = 0
        for (i in 0 until 20) {
            val result = service.sendMessageBlocking("ref", chatId = 1L, msg = "msg$i", threadId = null, threadScope = null)
            if (result.status == ReplyAdmissionStatus.QUEUE_FULL) fullCount++
        }

        assertTrue(fullCount > 0, "at least one enqueue should be rejected as QUEUE_FULL")
        service.shutdownBlocking()
    }

    @Test
    fun `shutdown then enqueue returns SHUTDOWN`() {
        val service = ReplyService(testConfig)
        service.startBlocking()

        service.sendMessageBlocking("ref", chatId = 1L, msg = "drain", threadId = null, threadScope = null)
        service.shutdownBlocking()

        val result = service.sendMessageBlocking("ref", chatId = 1L, msg = "after", threadId = null, threadScope = null)
        assertEquals(ReplyAdmissionStatus.SHUTDOWN, result.status)
    }

    @Test
    fun `same key messages are all accepted in order`() {
        val service = ReplyService(testConfig)
        service.startBlocking()

        val results =
            (1..10).map { i ->
                service.sendMessageBlocking("ref", chatId = 1L, msg = "msg$i", threadId = 100L, threadScope = 1)
            }

        results.forEach { assertEquals(ReplyAdmissionStatus.ACCEPTED, it.status) }
        service.shutdownBlocking()
    }

    @Test
    fun `different keys create independent workers`() {
        val service = ReplyService(testConfig)
        service.startBlocking()

        val result1 = service.sendMessageBlocking("ref", chatId = 1L, msg = "a", threadId = 100L, threadScope = 1)
        val result2 = service.sendMessageBlocking("ref", chatId = 1L, msg = "b", threadId = 200L, threadScope = 1)
        val result3 = service.sendMessageBlocking("ref", chatId = 2L, msg = "c", threadId = null, threadScope = null)

        assertEquals(ReplyAdmissionStatus.ACCEPTED, result1.status)
        assertEquals(ReplyAdmissionStatus.ACCEPTED, result2.status)
        assertEquals(ReplyAdmissionStatus.ACCEPTED, result3.status)

        service.shutdownBlocking()
    }

    @Test
    fun `new worker is created after restart for same key`() {
        val service = ReplyService(testConfig)
        service.startBlocking()

        val result1 = service.sendMessageBlocking("ref", chatId = 1L, msg = "before", threadId = null, threadScope = null)
        assertEquals(ReplyAdmissionStatus.ACCEPTED, result1.status)

        service.restartBlocking()

        val result2 = service.sendMessageBlocking("ref", chatId = 1L, msg = "after", threadId = null, threadScope = null)
        assertEquals(ReplyAdmissionStatus.ACCEPTED, result2.status)

        service.shutdownBlocking()
    }

    @Test
    fun `same key preserves send order`() =
        runTest {
            val service = deterministicReplyService(scheduler = testScheduler)
            service.startSuspend()
            val executed = CopyOnWriteArrayList<Int>()

            for (i in 0 until 5) {
                service.enqueueActionSuspend(chatId = 1L, threadId = 100L) {
                    executed.add(i)
                }
            }

            advanceUntilIdle()
            assertEquals(listOf(0, 1, 2, 3, 4), executed)
            service.shutdownSuspend()
        }

    @Test
    fun `global gate paces send start times across workers`() =
        runTest {
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
            val service = deterministicReplyService(config = pacingConfig, scheduler = testScheduler)
            service.startSuspend()
            val timestamps = mutableListOf<Long>()

            for (i in 0L until 2L) {
                repeat(2) {
                    service.enqueueActionSuspend(chatId = i, threadId = null, lane = ReplySendLane.TEXT) {
                        timestamps.add(testScheduler.currentTime)
                        delay(50)
                    }
                }
            }

            advanceUntilIdle()
            assertEquals(4, timestamps.size)
            val sorted = timestamps.sorted()
            for (i in 1 until sorted.size) {
                val gap = sorted[i] - sorted[i - 1]
                assertTrue(gap >= 100, "send starts should be paced by at least 100ms (gap was ${gap}ms)")
            }
            service.shutdownSuspend()
        }

    @Test
    fun `global gate paces send start times across all lanes`() =
        runTest {
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
            val service = deterministicReplyService(config = pacingConfig, scheduler = testScheduler)
            service.startSuspend()
            val timestamps = mutableListOf<Long>()

            service.enqueueActionSuspend(chatId = 1L, threadId = null, lane = ReplySendLane.TEXT) {
                timestamps.add(testScheduler.currentTime)
                delay(50)
            }
            service.enqueueActionSuspend(chatId = 2L, threadId = null, lane = ReplySendLane.NATIVE_IMAGE) {
                timestamps.add(testScheduler.currentTime)
                delay(50)
            }

            advanceUntilIdle()
            assertEquals(2, timestamps.size)
            val sorted = timestamps.sorted()
            val gap = sorted[1] - sorted[0]
            assertTrue(gap >= 100, "all lanes should respect shared pacing (gap was ${gap}ms)")
            service.shutdownSuspend()
        }

    @Test
    fun `shutdown drains pending messages`() =
        runTest {
            val service = deterministicReplyService(scheduler = testScheduler)
            service.startSuspend()
            val executed = AtomicInteger(0)

            repeat(3) {
                service.enqueueActionSuspend(chatId = 1L, threadId = null) {
                    delay(20)
                    executed.incrementAndGet()
                }
            }

            service.shutdownSuspend()
            advanceUntilIdle()
            assertEquals(3, executed.get(), "all pending messages should be drained before shutdown completes")
        }

    @Test
    fun `global gate enforces pacing between sends from different workers`() =
        runTest {
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
            val service = deterministicReplyService(config = pacingConfig, scheduler = testScheduler)
            service.startSuspend()
            val timestamps = mutableListOf<Long>()

            for (i in 0L until 3L) {
                service.enqueueActionSuspend(chatId = i, threadId = null) {
                    timestamps.add(testScheduler.currentTime)
                }
            }

            advanceUntilIdle()
            assertEquals(3, timestamps.size)
            val sorted = timestamps.sorted()
            for (i in 1 until sorted.size) {
                val gap = sorted[i] - sorted[i - 1]
                assertTrue(gap >= 100, "sends should be paced by at least 100ms (gap was ${gap}ms)")
            }
            service.shutdownSuspend()
        }

    @Test
    fun `shutdown terminates workers blocked in dispatch gate pacing`() =
        runTest {
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
            val service = deterministicReplyService(config = pacingConfig, scheduler = testScheduler)
            service.startSuspend()

            service.enqueueActionSuspend(chatId = 1L, threadId = null) {
                Unit
            }
            runCurrent()

            val blockedRequestId = "gate-blocked"
            service.enqueueActionSuspend(chatId = 2L, threadId = null, requestId = blockedRequestId) {}
            runCurrent()
            assertTrue(service.replyStatusOrNull(blockedRequestId) != null, "queued request should be tracked before shutdown")

            val start = testScheduler.currentTime
            service.shutdownSuspend()
            val elapsed = testScheduler.currentTime - start

            assertTrue(elapsed < 10_000, "shutdown should complete within admission shutdown timeout (took ${elapsed}ms)")
        }

    @Test
    fun `sendNativePhoto rejects invalid payload before native bootstrap`() {
        val service = ReplyService(testConfig)
        service.startBlocking()

        val result =
            service.sendNativePhotoBytesBlocking(
                room = 18478615493603057L,
                imageBytes = ByteArray(0),
                threadId = 3804154209723703299L,
                threadScope = 2,
            )

        assertEquals(ReplyAdmissionStatus.INVALID_PAYLOAD, result.status)
        service.shutdownBlocking()
    }

    @Test
    fun `sendNativeMultiplePhotos rejects when image count exceeds limit`() {
        val service = ReplyService(testConfig)
        service.startBlocking()

        val result =
            service.sendNativeMultiplePhotosBytesBlocking(
                room = 18478615493603057L,
                imageBytesList = List(9) { VALID_TEST_PNG_BYTES },
                threadId = null,
                threadScope = null,
            )

        assertEquals(ReplyAdmissionStatus.INVALID_PAYLOAD, result.status)
        service.shutdownBlocking()
    }

    @Test
    fun `sendNativePhoto admission does not wait for image persistence`() =
        runTest {
            val sendStarted = CompletableDeferred<Unit>()
            val allowSendToFinish = CompletableDeferred<Unit>()
            val imageDir = Files.createTempDirectory("iris-reply-deferred-save").toFile()
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
                                runBlocking {
                                    sendStarted.complete(Unit)
                                    allowSendToFinish.await()
                                }
                            }
                        },
                    mediaScanner = {},
                    imageDir = imageDir,
                )
            try {
                service.startSuspend()

                val resultDeferred =
                    async {
                        service.sendNativePhotoBytesSuspend(
                            room = 18478615493603057L,
                            imageBytes = VALID_TEST_PNG_BYTES,
                            threadId = null,
                            threadScope = null,
                            requestId = null,
                        )
                    }

                sendStarted.await()

                assertTrue(resultDeferred.isCompleted, "queue admission should not block on image persistence")
                assertEquals(ReplyAdmissionStatus.ACCEPTED, resultDeferred.await().status)

                allowSendToFinish.complete(Unit)
                service.shutdownSuspend()
            } finally {
                allowSendToFinish.complete(Unit)
                imageDir.deleteRecursively()
            }
        }

    @Test
    fun `sendNativePhoto keeps prepared files after successful native send`() =
        runTest {
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
                            }
                        },
                    mediaScanner = {},
                    imageDir = imageDir,
                    admissionDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler),
                    dispatchClock = { testScheduler.currentTime },
                    statusTickerNanos = { testScheduler.currentTime * 1_000_000L },
                    statusUpdatedAtEpochMs = { testScheduler.currentTime },
                )
            try {
                service.startSuspend()

                val result =
                    service.sendNativePhotoBytesSuspend(
                        room = 18478615493603057L,
                        imageBytes = VALID_TEST_PNG_BYTES,
                        threadId = null,
                        threadScope = null,
                        requestId = null,
                    )

                assertEquals(ReplyAdmissionStatus.ACCEPTED, result.status)
                advanceUntilIdle()
                assertTrue(observedPaths.isNotEmpty(), "native sender should observe prepared image paths")
                assertTrue(observedPaths.all { path -> File(path).exists() }, "prepared files should remain after native send")
                service.shutdownSuspend()
            } finally {
                imageDir.deleteRecursively()
            }
        }

    @Test
    fun `validateImageBytesPayload rejects when total exceeds limit`() {
        val payloads = listOf(ByteArray(4), ByteArray(4), ByteArray(4))

        assertFailsWith<IllegalArgumentException> {
            validateImageBytesPayload(
                imageBytesList = payloads,
                maxImagesPerRequest = 8,
                maxTotalBytes = 10,
            )
        }
    }

    @Test
    fun `request id status advances through queue and send`() =
        runTest {
            val service = deterministicReplyService(scheduler = testScheduler)
            service.startSuspend()
            val requestId = "req-123"

            val result =
                service.enqueueActionSuspend(
                    chatId = 1L,
                    threadId = null,
                    lane = ReplySendLane.TEXT,
                    requestId = requestId,
                ) {
                    Unit
                }

            assertEquals(ReplyAdmissionStatus.ACCEPTED, result.status)
            advanceUntilIdle()
            assertEquals(ReplyLifecycleState.HANDOFF_COMPLETED, service.replyStatusOrNull(requestId)?.state)
            service.shutdownSuspend()
        }

    @Test
    fun `threaded text replies use share graft lane instead of notification reply lane`() =
        runTest {
            val notificationStarts = AtomicInteger(0)
            val shareStarts = AtomicInteger(0)
            val service =
                ReplyService(
                    testConfig,
                    notificationReplySender = { _, _, _, _, _ ->
                        notificationStarts.incrementAndGet()
                    },
                    sharedTextReplySender = { _, _, _, _ ->
                        shareStarts.incrementAndGet()
                    },
                    admissionDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler),
                    dispatchClock = { testScheduler.currentTime },
                    statusTickerNanos = { testScheduler.currentTime * 1_000_000L },
                    statusUpdatedAtEpochMs = { testScheduler.currentTime },
                )
            service.startSuspend()

            val result =
                service.sendMessageSuspend(
                    referer = "ref",
                    chatId = 18478615493603057L,
                    msg = "**thread text**",
                    threadId = 3805486995143352321L,
                    threadScope = 2,
                    requestId = null,
                )

            assertEquals(ReplyAdmissionStatus.ACCEPTED, result.status)
            advanceUntilIdle()
            assertEquals(0, notificationStarts.get())
            assertEquals(1, shareStarts.get())
            service.shutdownSuspend()
        }

    @Test
    fun `room text replies keep notification reply lane`() =
        runTest {
            val notificationStarts = AtomicInteger(0)
            val shareStarts = AtomicInteger(0)
            val service =
                ReplyService(
                    testConfig,
                    notificationReplySender = { _, _, _, _, _ ->
                        notificationStarts.incrementAndGet()
                    },
                    sharedTextReplySender = { _, _, _, _ ->
                        shareStarts.incrementAndGet()
                    },
                    admissionDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler),
                    dispatchClock = { testScheduler.currentTime },
                    statusTickerNanos = { testScheduler.currentTime * 1_000_000L },
                    statusUpdatedAtEpochMs = { testScheduler.currentTime },
                )
            service.startSuspend()

            val result =
                service.sendMessageSuspend(
                    referer = "ref",
                    chatId = 18478615493603057L,
                    msg = "plain room text",
                    threadId = null,
                    threadScope = null,
                    requestId = null,
                )

            assertEquals(ReplyAdmissionStatus.ACCEPTED, result.status)
            advanceUntilIdle()
            assertEquals(1, notificationStarts.get())
            assertEquals(0, shareStarts.get())
            service.shutdownSuspend()
        }

    @Test
    fun `threaded text replies default share graft scope to two when omitted`() =
        runTest {
            val capturedScope = AtomicInteger(-1)
            val service =
                ReplyService(
                    testConfig,
                    sharedTextReplySender = { _, _, _, threadScope ->
                        capturedScope.set(threadScope ?: -1)
                    },
                    admissionDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler),
                    dispatchClock = { testScheduler.currentTime },
                    statusTickerNanos = { testScheduler.currentTime * 1_000_000L },
                    statusUpdatedAtEpochMs = { testScheduler.currentTime },
                )
            service.startSuspend()

            val result =
                service.sendMessageSuspend(
                    referer = "ref",
                    chatId = 18478615493603057L,
                    msg = "thread scope default",
                    threadId = 3805486995143352321L,
                    threadScope = null,
                    requestId = null,
                )

            assertEquals(ReplyAdmissionStatus.ACCEPTED, result.status)
            advanceUntilIdle()
            assertEquals(2, capturedScope.get())
            service.shutdownSuspend()
        }

    @Test
    fun `reply markdown alias uses shared text reply lane`() =
        runTest {
            val shareStarts = AtomicInteger(0)
            val capturedScope = AtomicInteger(-1)
            val service =
                ReplyService(
                    testConfig,
                    sharedTextReplySender = { _, _, _, threadScope ->
                        shareStarts.incrementAndGet()
                        capturedScope.set(threadScope ?: -1)
                    },
                    admissionDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler),
                    dispatchClock = { testScheduler.currentTime },
                    statusTickerNanos = { testScheduler.currentTime * 1_000_000L },
                    statusUpdatedAtEpochMs = { testScheduler.currentTime },
                )
            service.startSuspend()

            val result =
                service.sendReplyMarkdownSuspend(
                    room = 18478615493603057L,
                    msg = "**markdown alias**",
                    threadId = 3805486995143352321L,
                    threadScope = null,
                    requestId = null,
                )

            assertEquals(ReplyAdmissionStatus.ACCEPTED, result.status)
            advanceUntilIdle()
            assertEquals(1, shareStarts.get())
            assertEquals(2, capturedScope.get())
            service.shutdownSuspend()
        }
}

private fun ReplyService.startBlocking() =
    runBlocking {
        startSuspend()
    }

private fun ReplyService.restartBlocking() =
    runBlocking {
        restartSuspend()
    }

private fun ReplyService.shutdownBlocking() =
    runBlocking {
        shutdownSuspend()
    }

private fun ReplyService.sendMessageBlocking(
    referer: String,
    chatId: Long,
    msg: String,
    threadId: Long?,
    threadScope: Int?,
    requestId: String? = null,
): ReplyAdmissionResult =
    runBlocking {
        sendMessage(referer, chatId, msg, threadId, threadScope, requestId)
    }

private fun ReplyService.sendNativePhotoBytesBlocking(
    room: Long,
    imageBytes: ByteArray,
    threadId: Long? = null,
    threadScope: Int? = null,
    requestId: String? = null,
): ReplyAdmissionResult =
    runBlocking {
        sendNativePhotoBytes(room, imageBytes, threadId, threadScope, requestId)
    }

private fun ReplyService.sendNativeMultiplePhotosBytesBlocking(
    room: Long,
    imageBytesList: List<ByteArray>,
    threadId: Long? = null,
    threadScope: Int? = null,
    requestId: String? = null,
): ReplyAdmissionResult =
    runBlocking {
        sendNativeMultiplePhotosBytes(room, imageBytesList, threadId, threadScope, requestId)
    }

private fun ReplyService.enqueueActionBlocking(
    chatId: Long,
    threadId: Long?,
    lane: ReplySendLane = ReplySendLane.TEXT,
    requestId: String? = null,
    action: suspend () -> Unit,
): ReplyAdmissionResult =
    runBlocking {
        enqueueAction(chatId, threadId, lane, requestId, action)
    }
