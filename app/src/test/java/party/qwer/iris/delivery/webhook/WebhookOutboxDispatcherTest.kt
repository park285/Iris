package party.qwer.iris.delivery.webhook

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import party.qwer.iris.ConfigManager
import party.qwer.iris.DEFAULT_WEBHOOK_ROUTE
import party.qwer.iris.persistence.ClaimTransitionResult
import party.qwer.iris.persistence.ClaimedDelivery
import party.qwer.iris.persistence.FailureOutcome
import party.qwer.iris.persistence.PendingWebhookDelivery
import party.qwer.iris.persistence.WebhookDeliveryStore
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WebhookOutboxDispatcherTest {
    private val fakeClock = AtomicLong(1_000_000L)

    @Test
    fun `dispatcher retries claims when partition queue is saturated`() {
        val configPath = Files.createTempFile("iris-outbox-dispatcher", ".json").toFile().absolutePath
        val config = ConfigManager(configPath = configPath)
        val port = reservePort()
        config.setWebhookEndpoint(DEFAULT_WEBHOOK_ROUTE, "http://127.0.0.1:$port/webhook/iris")

        val releaseLatch = CountDownLatch(1)
        val store =
            RecordingWebhookDeliveryStore(
                claimedEntries =
                    listOf(
                        claimedEntry(id = 1L, roomId = 1L, messageId = "msg-1"),
                        claimedEntry(id = 2L, roomId = 1L, messageId = "msg-2"),
                        claimedEntry(id = 3L, roomId = 1L, messageId = "msg-3"),
                    ),
                onRelease = { releaseLatch.countDown() },
            )

        val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", port), 0).apply {
                createContext("/webhook/iris") { exchange ->
                    CountDownLatch(1).await(300, TimeUnit.MILLISECONDS)
                    exchange.sendResponseHeaders(204, -1)
                    exchange.close()
                }
                executor = Executors.newSingleThreadExecutor()
                start()
            }

        try {
            WebhookOutboxDispatcher(
                config = config,
                store = store,
                transportOverride = "http1",
                partitionCount = 1,
                deliveryPolicy =
                    WebhookDeliveryPolicy(
                        partitionQueueCapacity = 1,
                        pollIntervalMs = 25L,
                        maxClaimBatchSize = 10,
                    ),
                clock = fakeClock::get,
            ).use { dispatcher ->
                dispatcher.start()
                assertTrue(releaseLatch.await(5, TimeUnit.SECONDS))
            }
        } finally {
            server.stop(0)
            (server.executor as? java.util.concurrent.ExecutorService)
                ?.shutdownNow()
            Files.deleteIfExists(Path.of(configPath))
        }

        assertTrue(store.releasedIds.isNotEmpty())
        assertTrue(store.releasedIds.any { it in setOf(2L, 3L) })
        assertTrue(store.releaseReasons.any { it.contains("partition queue saturated") })
    }

    @Test
    fun `dispatcher periodically recovers expired claims while running`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val configPath = Files.createTempFile("iris-outbox-dispatcher", ".json").toFile().absolutePath
            val config = ConfigManager(configPath = configPath)
            val store = RecordingWebhookDeliveryStore(claimedEntries = emptyList())
            val recoveryInterval = 10_000L
            val outboxDispatcher =
                WebhookOutboxDispatcher(
                    config = config,
                    store = store,
                    deliveryPolicy =
                        WebhookDeliveryPolicy(
                            pollIntervalMs = 25L,
                            maxClaimBatchSize = 1,
                            claimRecoveryIntervalMs = recoveryInterval,
                        ),
                    clock = { testScheduler.currentTime },
                    dispatcher = dispatcher,
                )

            try {
                outboxDispatcher.start()
                assertEquals(1, store.recoverCallCount.get())
                runCurrent()
                advanceTimeBy(recoveryInterval + 25L)
                assertEquals(2, store.recoverCallCount.get())
            } finally {
                outboxDispatcher.closeSuspend()
                Files.deleteIfExists(Path.of(configPath))
            }

            assertEquals(listOf(60_000L, 60_000L), store.recoverOlderThanMs.take(2))
        }

    @Test
    fun `dispatcher recovers expired claims immediately on start`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val configPath = Files.createTempFile("iris-outbox-dispatcher", ".json").toFile().absolutePath
            val config = ConfigManager(configPath = configPath)
            val store = RecordingWebhookDeliveryStore(claimedEntries = emptyList())
            val outboxDispatcher =
                WebhookOutboxDispatcher(
                    config = config,
                    store = store,
                    deliveryPolicy =
                        WebhookDeliveryPolicy(
                            pollIntervalMs = 1_000L,
                            maxClaimBatchSize = 1,
                            claimRecoveryIntervalMs = 30_000L,
                        ),
                    clock = { testScheduler.currentTime },
                    dispatcher = dispatcher,
                )

            try {
                outboxDispatcher.start()
                assertEquals(1, store.recoverCallCount.get())
                runCurrent()
                assertEquals(listOf(60_000L), store.recoverOlderThanMs.toList())
            } finally {
                outboxDispatcher.closeSuspend()
                Files.deleteIfExists(Path.of(configPath))
            }
        }

    @Test
    fun `close after attempt start resolves failure instead of requeue`() {
        val requestStarted = CountDownLatch(1)
        val retryLatch = CountDownLatch(1)
        val port = reservePort()
        val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", port), 0).apply {
                createContext("/webhook/iris") { exchange ->
                    requestStarted.countDown()
                    CountDownLatch(1).await(5, TimeUnit.SECONDS)
                    exchange.sendResponseHeaders(204, -1)
                    exchange.close()
                }
                executor = Executors.newSingleThreadExecutor()
                start()
            }
        val configPath = Files.createTempFile("iris-outbox-dispatcher", ".json").toFile().absolutePath
        val config = ConfigManager(configPath = configPath)
        config.setWebhookEndpoint(DEFAULT_WEBHOOK_ROUTE, "http://127.0.0.1:$port/webhook/iris")
        val store =
            RecordingWebhookDeliveryStore(
                claimedEntries = listOf(claimedEntry(id = 1L, roomId = 1L, messageId = "msg-1")),
                onRetry = { retryLatch.countDown() },
            )
        val outboxDispatcher =
            WebhookOutboxDispatcher(
                config = config,
                store = store,
                transportOverride = "http1",
                deliveryPolicy =
                    WebhookDeliveryPolicy(
                        pollIntervalMs = 25L,
                        maxClaimBatchSize = 1,
                    ),
                clock = fakeClock::get,
            )

        try {
            outboxDispatcher.start()
            assertTrue(requestStarted.await(5, TimeUnit.SECONDS), "dispatcher should start processing claimed entry")
            runBlocking { outboxDispatcher.closeSuspend() }
            assertTrue(retryLatch.await(5, TimeUnit.SECONDS), "dispatcher should resolve failure during shutdown")
        } finally {
            server.stop(0)
            (server.executor as? java.util.concurrent.ExecutorService)
                ?.shutdownNow()
            Files.deleteIfExists(Path.of(configPath))
        }

        assertTrue(store.releasedIds.isEmpty(), "post-attempt shutdown must not requeue")
        assertTrue(store.retriedIds.contains(1L), "post-attempt shutdown should resolve as retry")
        assertTrue(store.resolvedOutcomes.single() is FailureOutcome.Retry)
    }

    @Test
    fun `shutdown after successful http attempt still marks sent`() {
        val requestStarted = CountDownLatch(1)
        val sentLatch = CountDownLatch(1)
        val port = reservePort()
        val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", port), 0).apply {
                createContext("/webhook/iris") { exchange ->
                    requestStarted.countDown()
                    exchange.sendResponseHeaders(204, -1)
                    exchange.close()
                }
                executor = Executors.newSingleThreadExecutor()
                start()
            }
        val configPath = Files.createTempFile("iris-outbox-sent-shutdown", ".json").toFile().absolutePath
        val config = ConfigManager(configPath = configPath)
        config.setWebhookEndpoint(DEFAULT_WEBHOOK_ROUTE, "http://127.0.0.1:$port/webhook/iris")
        val store =
            RecordingWebhookDeliveryStore(
                claimedEntries = listOf(claimedEntry(id = 1L, roomId = 1L, messageId = "msg-sent-shutdown")),
                onMarkSent = { sentLatch.countDown() },
            )

        try {
            WebhookOutboxDispatcher(
                config = config,
                store = store,
                transportOverride = "http1",
                deliveryPolicy = WebhookDeliveryPolicy(pollIntervalMs = 25L, maxClaimBatchSize = 1),
                clock = fakeClock::get,
            ).use { dispatcher ->
                dispatcher.start()
                assertTrue(sentLatch.await(5, TimeUnit.SECONDS), "dispatcher should markSent even if shutdown is imminent")
            }
        } finally {
            server.stop(0)
            (server.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
            Files.deleteIfExists(Path.of(configPath))
        }

        assertTrue(store.releasedIds.isEmpty(), "successful delivery must not requeue")
        assertTrue(store.resolvedOutcomes.isEmpty(), "successful delivery must not resolveFailure")
    }

    @Test
    fun `shutdown requeues queued entries that never started attempt`() {
        val requestStarted = CountDownLatch(1)
        val port = reservePort()
        val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", port), 0).apply {
                createContext("/webhook/iris") { exchange ->
                    requestStarted.countDown()
                    CountDownLatch(1).await(10, TimeUnit.SECONDS)
                    exchange.sendResponseHeaders(204, -1)
                    exchange.close()
                }
                executor = Executors.newSingleThreadExecutor()
                start()
            }
        val configPath = Files.createTempFile("iris-outbox-pre-attempt", ".json").toFile().absolutePath
        val config = ConfigManager(configPath = configPath)
        config.setWebhookEndpoint(DEFAULT_WEBHOOK_ROUTE, "http://127.0.0.1:$port/webhook/iris")
        val releaseLatch = CountDownLatch(1)
        val store =
            RecordingWebhookDeliveryStore(
                claimedEntries =
                    listOf(
                        claimedEntry(id = 1L, roomId = 1L, messageId = "msg-inflight"),
                        claimedEntry(id = 2L, roomId = 1L, messageId = "msg-queued"),
                    ),
                onRelease = { releaseLatch.countDown() },
            )

        try {
            val outboxDispatcher =
                WebhookOutboxDispatcher(
                    config = config,
                    store = store,
                    transportOverride = "http1",
                    partitionCount = 1,
                    deliveryPolicy =
                        WebhookDeliveryPolicy(
                            pollIntervalMs = 25L,
                            maxClaimBatchSize = 10,
                        ),
                    clock = fakeClock::get,
                )

            outboxDispatcher.start()
            assertTrue(requestStarted.await(5, TimeUnit.SECONDS), "first entry should start HTTP attempt")
            runBlocking { outboxDispatcher.closeSuspend() }
            assertTrue(releaseLatch.await(5, TimeUnit.SECONDS), "queued entry should be released")
        } finally {
            server.stop(0)
            (server.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
            Files.deleteIfExists(Path.of(configPath))
        }

        assertTrue(store.retriedIds.contains(1L), "in-flight entry should resolve as retry, not requeue")
        assertTrue(store.releasedIds.contains(2L), "queued entry should be requeued without attempt count")
        assertTrue(store.resolvedOutcomes.all { it is FailureOutcome.Retry }, "only in-flight entry generates resolvedOutcome")
    }

    @Test
    fun `missing webhook URL resolves as RejectedBeforeAttempt`() {
        val configPath = Files.createTempFile("iris-outbox-no-url", ".json").toFile().absolutePath
        val config = ConfigManager(configPath = configPath)
        val latch = CountDownLatch(1)
        val store =
            RecordingWebhookDeliveryStore(
                claimedEntries = listOf(claimedEntry(id = 1L, roomId = 1L, messageId = "msg-no-url")),
                onDead = { latch.countDown() },
            )

        try {
            WebhookOutboxDispatcher(
                config = config,
                store = store,
                deliveryPolicy = WebhookDeliveryPolicy(pollIntervalMs = 25L, maxClaimBatchSize = 1),
                clock = fakeClock::get,
            ).use { dispatcher ->
                dispatcher.start()
                assertTrue(latch.await(5, TimeUnit.SECONDS))
            }
        } finally {
            Files.deleteIfExists(Path.of(configPath))
        }

        assertEquals(1, store.resolvedOutcomes.size)
        assertTrue(store.resolvedOutcomes.single() is FailureOutcome.RejectedBeforeAttempt)
    }

    @Test
    fun `pre attempt request creation failure resolves as RejectedBeforeAttempt not requeue`() {
        val configPath = Files.createTempFile("iris-outbox-invalid-request", ".json").toFile().absolutePath
        val config = ConfigManager(configPath = configPath)
        config.setWebhookEndpoint(DEFAULT_WEBHOOK_ROUTE, "http://127.0.0.1:65535/webhook/iris")

        val deadLatch = CountDownLatch(1)
        val store =
            RecordingWebhookDeliveryStore(
                claimedEntries = listOf(claimedEntry(id = 1L, roomId = 1L, messageId = "msg-invalid")),
                onDead = { deadLatch.countDown() },
            )

        try {
            WebhookOutboxDispatcher(
                config = config,
                store = store,
                deliveryPolicy = WebhookDeliveryPolicy(pollIntervalMs = 25L, maxClaimBatchSize = 1),
                clock = fakeClock::get,
                createRequestOverride = { throw DeterministicPreAttemptRejectException("invalid payload") },
            ).use { dispatcher ->
                dispatcher.start()
                assertTrue(deadLatch.await(5, TimeUnit.SECONDS))
            }
        } finally {
            Files.deleteIfExists(Path.of(configPath))
        }

        assertTrue(store.releasedIds.isEmpty())
        assertEquals(1, store.resolvedOutcomes.size)
        assertTrue(store.resolvedOutcomes.single() is FailureOutcome.RejectedBeforeAttempt)
    }

    @Test
    fun `unexpected pre attempt local exception resolves retry not dead letter`() {
        val configPath = Files.createTempFile("iris-outbox-unexpected-pre-attempt", ".json").toFile().absolutePath
        val config = ConfigManager(configPath = configPath)
        config.setWebhookEndpoint(DEFAULT_WEBHOOK_ROUTE, "http://127.0.0.1:65535/webhook/iris")

        val retryLatch = CountDownLatch(1)
        val store =
            RecordingWebhookDeliveryStore(
                claimedEntries = listOf(claimedEntry(id = 1L, roomId = 1L, messageId = "msg-unexpected-pre-attempt")),
                onRetry = { retryLatch.countDown() },
            )

        try {
            WebhookOutboxDispatcher(
                config = config,
                store = store,
                deliveryPolicy = WebhookDeliveryPolicy(pollIntervalMs = 25L, maxClaimBatchSize = 1),
                clock = fakeClock::get,
                createRequestOverride = { throw IllegalStateException("unexpected bug") },
            ).use { dispatcher ->
                dispatcher.start()
                assertTrue(retryLatch.await(5, TimeUnit.SECONDS))
            }
        } finally {
            Files.deleteIfExists(Path.of(configPath))
        }

        assertTrue(store.releasedIds.isEmpty())
        assertEquals(1, store.resolvedOutcomes.size)
        assertTrue(store.resolvedOutcomes.single() is FailureOutcome.Retry)
    }

    @Test
    fun `delivery timeout after attempt start resolves retry instead of requeue`() {
        val configPath = Files.createTempFile("iris-outbox-timeout", ".json").toFile().absolutePath
        val config = ConfigManager(configPath = configPath)
        config.setWebhookEndpoint(DEFAULT_WEBHOOK_ROUTE, "http://127.0.0.1:65535/webhook/iris")

        val retryLatch = CountDownLatch(1)
        val store =
            RecordingWebhookDeliveryStore(
                claimedEntries = listOf(claimedEntry(id = 1L, roomId = 1L, messageId = "msg-timeout")),
                onRetry = { retryLatch.countDown() },
            )

        try {
            WebhookOutboxDispatcher(
                config = config,
                store = store,
                deliveryPolicy =
                    WebhookDeliveryPolicy(
                        pollIntervalMs = 25L,
                        maxClaimBatchSize = 1,
                        deliveryTimeoutMs = 200L,
                        claimRecoveryIntervalMs = 500L,
                        claimExpirationMs = 2_000L,
                        claimHeartbeatIntervalMs = 100L,
                    ),
                clock = fakeClock::get,
                createRequestOverride = { dummyRequest("http://127.0.0.1:65535/webhook/iris") },
                executeRequestOverride = { _, _ ->
                    delay(500L)
                    204
                },
            ).use { dispatcher ->
                dispatcher.start()
                assertTrue(retryLatch.await(5, TimeUnit.SECONDS))
            }
        } finally {
            Files.deleteIfExists(Path.of(configPath))
        }

        assertTrue(store.releasedIds.isEmpty())
        assertEquals(1, store.resolvedOutcomes.size)
        assertTrue(store.resolvedOutcomes.single() is FailureOutcome.Retry)
    }

    @Test
    fun `long running delivery renews claim while in flight`() {
        val configPath = Files.createTempFile("iris-outbox-heartbeat", ".json").toFile().absolutePath
        val config = ConfigManager(configPath = configPath)
        config.setWebhookEndpoint(DEFAULT_WEBHOOK_ROUTE, "http://127.0.0.1:65535/webhook/iris")

        val renewLatch = CountDownLatch(2)
        val sentLatch = CountDownLatch(1)
        val store =
            RecordingWebhookDeliveryStore(
                claimedEntries = listOf(claimedEntry(id = 1L, roomId = 1L, messageId = "msg-heartbeat")),
                onRenew = { renewLatch.countDown() },
                onMarkSent = { sentLatch.countDown() },
            )

        try {
            WebhookOutboxDispatcher(
                config = config,
                store = store,
                deliveryPolicy =
                    WebhookDeliveryPolicy(
                        pollIntervalMs = 25L,
                        maxClaimBatchSize = 1,
                        deliveryTimeoutMs = 1_000L,
                        claimRecoveryIntervalMs = 500L,
                        claimExpirationMs = 2_100L,
                        claimHeartbeatIntervalMs = 100L,
                    ),
                clock = fakeClock::get,
                createRequestOverride = { dummyRequest("http://127.0.0.1:65535/webhook/iris") },
                executeRequestOverride = { _, _ ->
                    delay(350L)
                    204
                },
            ).use { dispatcher ->
                dispatcher.start()
                assertTrue(renewLatch.await(5, TimeUnit.SECONDS))
                assertTrue(sentLatch.await(5, TimeUnit.SECONDS))
            }
        } finally {
            Files.deleteIfExists(Path.of(configPath))
        }

        assertTrue(store.renewedIds.contains(1L))
        assertTrue(store.releasedIds.isEmpty())
    }

    @Test
    fun `non retryable status resolves as PermanentFailure`() {
        val port = reservePort()
        val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", port), 0).apply {
                createContext("/webhook/iris") { exchange ->
                    exchange.sendResponseHeaders(400, -1)
                    exchange.close()
                }
                executor = Executors.newSingleThreadExecutor()
                start()
            }
        val configPath = Files.createTempFile("iris-outbox-400", ".json").toFile().absolutePath
        val config = ConfigManager(configPath = configPath)
        config.setWebhookEndpoint(DEFAULT_WEBHOOK_ROUTE, "http://127.0.0.1:$port/webhook/iris")
        val latch = CountDownLatch(1)
        val store =
            RecordingWebhookDeliveryStore(
                claimedEntries = listOf(claimedEntry(id = 1L, roomId = 1L, messageId = "msg-400")),
                onDead = { latch.countDown() },
            )

        try {
            WebhookOutboxDispatcher(
                config = config,
                store = store,
                transportOverride = "http1",
                deliveryPolicy = WebhookDeliveryPolicy(pollIntervalMs = 25L, maxClaimBatchSize = 1),
                clock = fakeClock::get,
            ).use { dispatcher ->
                dispatcher.start()
                assertTrue(latch.await(5, TimeUnit.SECONDS))
            }
        } finally {
            server.stop(0)
            (server.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
            Files.deleteIfExists(Path.of(configPath))
        }

        assertEquals(1, store.resolvedOutcomes.size)
        assertTrue(store.resolvedOutcomes.single() is FailureOutcome.PermanentFailure)
    }

    @Test
    fun `retryable status resolves as Retry before exhaustion`() {
        val port = reservePort()
        val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", port), 0).apply {
                createContext("/webhook/iris") { exchange ->
                    exchange.sendResponseHeaders(503, -1)
                    exchange.close()
                }
                executor = Executors.newSingleThreadExecutor()
                start()
            }
        val configPath = Files.createTempFile("iris-outbox-503-retry", ".json").toFile().absolutePath
        val config = ConfigManager(configPath = configPath)
        config.setWebhookEndpoint(DEFAULT_WEBHOOK_ROUTE, "http://127.0.0.1:$port/webhook/iris")
        val retryLatch = CountDownLatch(1)
        val store =
            RecordingWebhookDeliveryStore(
                claimedEntries = listOf(claimedEntry(id = 1L, roomId = 1L, messageId = "msg-503-retry")),
                onRetry = { retryLatch.countDown() },
            )

        try {
            WebhookOutboxDispatcher(
                config = config,
                store = store,
                transportOverride = "http1",
                deliveryPolicy =
                    WebhookDeliveryPolicy(
                        pollIntervalMs = 25L,
                        maxClaimBatchSize = 1,
                        maxDeliveryAttempts = 6,
                    ),
                clock = fakeClock::get,
            ).use { dispatcher ->
                dispatcher.start()
                assertTrue(retryLatch.await(5, TimeUnit.SECONDS))
            }
        } finally {
            server.stop(0)
            (server.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
            Files.deleteIfExists(Path.of(configPath))
        }

        assertEquals(1, store.resolvedOutcomes.size)
        assertTrue(store.resolvedOutcomes.single() is FailureOutcome.Retry)
    }

    @Test
    fun `dispatcher surfaces stale markSent result to observer`() {
        val observed = CopyOnWriteArrayList<String>()
        val observer =
            ClaimTransitionObserver { operation, _, result ->
                if (result == ClaimTransitionResult.STALE_CLAIM) observed += operation
            }
        val port = reservePort()
        val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", port), 0).apply {
                createContext("/webhook/iris") { exchange ->
                    exchange.sendResponseHeaders(204, -1)
                    exchange.close()
                }
                executor = Executors.newSingleThreadExecutor()
                start()
            }
        val configPath = Files.createTempFile("iris-outbox-stale", ".json").toFile().absolutePath
        val config = ConfigManager(configPath = configPath)
        config.setWebhookEndpoint(DEFAULT_WEBHOOK_ROUTE, "http://127.0.0.1:$port/webhook/iris")
        val sentLatch = CountDownLatch(1)
        val store =
            RecordingWebhookDeliveryStore(
                claimedEntries = listOf(claimedEntry(id = 1L, roomId = 1L, messageId = "msg-stale")),
                markSentResult = ClaimTransitionResult.STALE_CLAIM,
                onMarkSent = { sentLatch.countDown() },
            )

        try {
            WebhookOutboxDispatcher(
                config = config,
                store = store,
                transportOverride = "http1",
                deliveryPolicy = WebhookDeliveryPolicy(pollIntervalMs = 25L, maxClaimBatchSize = 1),
                claimTransitionObserver = observer,
                clock = fakeClock::get,
            ).use { dispatcher ->
                dispatcher.start()
                assertTrue(sentLatch.await(5, TimeUnit.SECONDS))
            }
        } finally {
            server.stop(0)
            (server.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
            Files.deleteIfExists(Path.of(configPath))
        }

        assertEquals(listOf("markSent"), observed.toList())
    }

    @Test
    fun `dispatcher uses delivery policy max attempts instead of legacy dispatcher constant`() {
        val configPath = Files.createTempFile("iris-outbox-policy", ".json").toFile().absolutePath
        val config = ConfigManager(configPath = configPath)
        val port = reservePort()
        config.setWebhookEndpoint(DEFAULT_WEBHOOK_ROUTE, "http://127.0.0.1:$port/webhook/iris")
        val deadLatch = CountDownLatch(1)
        val store =
            RecordingWebhookDeliveryStore(
                claimedEntries = listOf(claimedEntry(id = 1L, roomId = 1L, messageId = "msg-policy")),
                onDead = { deadLatch.countDown() },
            )
        val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", port), 0).apply {
                createContext("/webhook/iris") { exchange ->
                    exchange.sendResponseHeaders(503, -1)
                    exchange.close()
                }
                executor = Executors.newSingleThreadExecutor()
                start()
            }

        try {
            WebhookOutboxDispatcher(
                config = config,
                store = store,
                transportOverride = "http1",
                deliveryPolicy = WebhookDeliveryPolicy(maxDeliveryAttempts = 1),
                clock = fakeClock::get,
            ).use { dispatcher ->
                dispatcher.start()
                assertTrue(deadLatch.await(5, TimeUnit.SECONDS))
            }
        } finally {
            server.stop(0)
            (server.executor as? java.util.concurrent.ExecutorService)
                ?.shutdownNow()
            Files.deleteIfExists(Path.of(configPath))
        }

        assertEquals(listOf(1L), store.deadIds.toList())
        assertTrue(store.retriedIds.isEmpty())
        assertTrue(store.deadReasons.any { it.contains("status=503") })
        assertTrue(store.resolvedOutcomes.single() is FailureOutcome.PermanentFailure)
    }

    private fun claimedEntry(
        id: Long,
        roomId: Long,
        messageId: String,
    ): ClaimedDelivery =
        ClaimedDelivery(
            id = id,
            roomId = roomId,
            route = DEFAULT_WEBHOOK_ROUTE,
            messageId = messageId,
            payloadJson = """{"messageId":"$messageId"}""",
            failedAttemptCount = 0,
            claimToken = "test-token",
        )

    private fun reservePort(): Int =
        ServerSocket(0).use { socket ->
            socket.localPort
        }
}

private class RecordingWebhookDeliveryStore(
    private val claimedEntries: List<ClaimedDelivery>,
    private val markSentResult: ClaimTransitionResult = ClaimTransitionResult.APPLIED,
    private val resolveFailureResult: ClaimTransitionResult = ClaimTransitionResult.APPLIED,
    private val renewClaimResult: ClaimTransitionResult = ClaimTransitionResult.APPLIED,
    private val requeueResult: ClaimTransitionResult = ClaimTransitionResult.APPLIED,
    private val onMarkSent: () -> Unit = {},
    private val onRetry: () -> Unit = {},
    private val onRenew: () -> Unit = {},
    private val onRelease: () -> Unit = {},
    private val onDead: () -> Unit = {},
) : WebhookDeliveryStore {
    private var claimed = false
    val retriedIds = CopyOnWriteArrayList<Long>()
    val retryReasons = CopyOnWriteArrayList<String>()
    val renewedIds = CopyOnWriteArrayList<Long>()
    val releasedIds = CopyOnWriteArrayList<Long>()
    val releaseReasons = CopyOnWriteArrayList<String>()
    val deadIds = CopyOnWriteArrayList<Long>()
    val deadReasons = CopyOnWriteArrayList<String>()
    val resolvedOutcomes = CopyOnWriteArrayList<FailureOutcome>()
    val recoverOlderThanMs = CopyOnWriteArrayList<Long>()
    val recoverCallCount = AtomicInteger(0)

    override fun enqueue(delivery: PendingWebhookDelivery): Long = 0L

    override fun claimReady(limit: Int): List<ClaimedDelivery> {
        if (claimed) return emptyList()
        claimed = true
        return claimedEntries.take(limit)
    }

    override fun markSent(
        id: Long,
        claimToken: String,
    ): ClaimTransitionResult {
        onMarkSent()
        return markSentResult
    }

    override fun resolveFailure(
        id: Long,
        claimToken: String,
        outcome: FailureOutcome,
    ): ClaimTransitionResult {
        resolvedOutcomes += outcome
        when (outcome) {
            is FailureOutcome.Retry -> {
                retriedIds += id
                retryReasons += outcome.reason.orEmpty()
                onRetry()
            }
            is FailureOutcome.PermanentFailure -> {
                deadIds += id
                deadReasons += outcome.reason.orEmpty()
                onDead()
            }
            is FailureOutcome.RejectedBeforeAttempt -> {
                deadIds += id
                deadReasons += outcome.reason.orEmpty()
                onDead()
            }
        }
        return resolveFailureResult
    }

    override fun renewClaim(
        id: Long,
        claimToken: String,
    ): ClaimTransitionResult {
        renewedIds += id
        onRenew()
        return renewClaimResult
    }

    override fun requeueClaim(
        id: Long,
        claimToken: String,
        nextAttemptAt: Long,
        reason: String?,
    ): ClaimTransitionResult {
        releasedIds += id
        releaseReasons += reason.orEmpty()
        onRelease()
        return requeueResult
    }

    override fun recoverExpiredClaims(olderThanMs: Long): Int {
        recoverOlderThanMs += olderThanMs
        recoverCallCount.incrementAndGet()
        return 0
    }

    override fun close() {}
}

private fun dummyRequest(url: String = "http://127.0.0.1/dummy"): Request =
    Request.Builder()
        .url(url)
        .post("{}".toRequestBody("application/json; charset=utf-8".toMediaType()))
        .build()
