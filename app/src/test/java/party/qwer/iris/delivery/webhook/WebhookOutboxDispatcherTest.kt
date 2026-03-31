package party.qwer.iris.delivery.webhook

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import party.qwer.iris.ConfigManager
import party.qwer.iris.DEFAULT_WEBHOOK_ROUTE
import party.qwer.iris.persistence.ClaimedDelivery
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
    fun `close releases outstanding claims immediately without incrementing attempt`() {
        val requestStarted = CountDownLatch(1)
        val releaseLatch = CountDownLatch(1)
        val port = reservePort()
        val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", port), 0).apply {
                createContext("/webhook/iris") { exchange ->
                    requestStarted.countDown()
                    CountDownLatch(1).await(300, TimeUnit.MILLISECONDS)
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
                onRelease = { releaseLatch.countDown() },
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
            assertTrue(releaseLatch.await(5, TimeUnit.SECONDS), "dispatcher should release claim during shutdown")
        } finally {
            server.stop(0)
            (server.executor as? java.util.concurrent.ExecutorService)
                ?.shutdownNow()
            Files.deleteIfExists(Path.of(configPath))
        }

        assertEquals(listOf(1L), store.releasedIds.toList())
        assertTrue(store.releaseReasons.contains("dispatcher shutdown"))
        assertTrue(store.retriedIds.isEmpty(), "graceful close should not increment retry attempt")
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
            attemptCount = 0,
            claimToken = "test-token",
        )

    private fun reservePort(): Int =
        ServerSocket(0).use { socket ->
            socket.localPort
        }
}

private class RecordingWebhookDeliveryStore(
    private val claimedEntries: List<ClaimedDelivery>,
    private val onRetry: () -> Unit = {},
    private val onRelease: () -> Unit = {},
    private val onDead: () -> Unit = {},
) : WebhookDeliveryStore {
    private var claimed = false
    val retriedIds = CopyOnWriteArrayList<Long>()
    val retryReasons = CopyOnWriteArrayList<String>()
    val releasedIds = CopyOnWriteArrayList<Long>()
    val releaseReasons = CopyOnWriteArrayList<String>()
    val deadIds = CopyOnWriteArrayList<Long>()
    val deadReasons = CopyOnWriteArrayList<String>()
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
    ) {}

    override fun markRetry(
        id: Long,
        claimToken: String,
        nextAttemptAt: Long,
        reason: String?,
    ) {
        retriedIds += id
        retryReasons += reason.orEmpty()
        onRetry()
    }

    override fun markDead(
        id: Long,
        claimToken: String,
        reason: String?,
    ) {
        deadIds += id
        deadReasons += reason.orEmpty()
        onDead()
    }

    override fun releaseClaim(
        id: Long,
        claimToken: String,
        nextAttemptAt: Long,
        reason: String?,
    ) {
        releasedIds += id
        releaseReasons += reason.orEmpty()
        onRelease()
    }

    override fun recoverExpiredClaims(olderThanMs: Long): Int {
        recoverOlderThanMs += olderThanMs
        recoverCallCount.incrementAndGet()
        return 0
    }

    override fun close() {}
}
