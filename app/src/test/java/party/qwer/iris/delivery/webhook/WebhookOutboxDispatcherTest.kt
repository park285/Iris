package party.qwer.iris.delivery.webhook

import com.sun.net.httpserver.HttpServer
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
import java.util.concurrent.locks.ReentrantLock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebhookOutboxDispatcherTest {
    @Test
    fun `dispatcher retries claims when partition queue is saturated`() {
        val configPath = Files.createTempFile("iris-outbox-dispatcher", ".json").toFile().absolutePath
        val config = ConfigManager(configPath = configPath)
        val port = reservePort()
        config.setWebhookEndpoint(DEFAULT_WEBHOOK_ROUTE, "http://127.0.0.1:$port/webhook/iris")

        val retryLatch = CountDownLatch(1)
        val store =
            RecordingWebhookDeliveryStore(
                claimedEntries =
                    listOf(
                        claimedEntry(id = 1L, roomId = 1L, messageId = "msg-1"),
                        claimedEntry(id = 2L, roomId = 1L, messageId = "msg-2"),
                        claimedEntry(id = 3L, roomId = 1L, messageId = "msg-3"),
                    ),
                onRetry = { retryLatch.countDown() },
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
                partitionQueueCapacity = 1,
                pollIntervalMs = 25L,
                maxClaimBatchSize = 10,
            ).use { dispatcher ->
                dispatcher.start()
                assertTrue(retryLatch.await(5, TimeUnit.SECONDS))
            }
        } finally {
            server.stop(0)
            (server.executor as? java.util.concurrent.ExecutorService)
                ?.shutdownNow()
            Files.deleteIfExists(Path.of(configPath))
        }

        assertTrue(store.retriedIds.isNotEmpty())
        assertTrue(store.retriedIds.any { it in setOf(2L, 3L) })
        assertTrue(store.retryReasons.any { it.contains("partition queue saturated") })
    }

    @Test
    fun `dispatcher periodically recovers expired claims while running`() {
        val configPath = Files.createTempFile("iris-outbox-dispatcher", ".json").toFile().absolutePath
        val config = ConfigManager(configPath = configPath)
        val store = RecordingWebhookDeliveryStore(claimedEntries = emptyList())

        try {
            WebhookOutboxDispatcher(
                config = config,
                store = store,
                pollIntervalMs = 25L,
                maxClaimBatchSize = 1,
                claimRecoveryIntervalMs = 50L,
            ).use { dispatcher ->
                dispatcher.start()
                assertTrue(store.awaitRecoveries(expectedCalls = 2, timeoutMs = 1_000L))
            }
        } finally {
            Files.deleteIfExists(Path.of(configPath))
        }

        assertTrue(store.recoverCallCount.get() >= 2)
        assertEquals(listOf(60_000L, 60_000L), store.recoverOlderThanMs.take(2))
    }

    @Test
    fun `dispatcher recovers expired claims immediately on start`() {
        val configPath = Files.createTempFile("iris-outbox-dispatcher", ".json").toFile().absolutePath
        val config = ConfigManager(configPath = configPath)
        val store = RecordingWebhookDeliveryStore(claimedEntries = emptyList())

        try {
            WebhookOutboxDispatcher(
                config = config,
                store = store,
                pollIntervalMs = 1_000L,
                maxClaimBatchSize = 1,
                claimRecoveryIntervalMs = 30_000L,
            ).use { dispatcher ->
                dispatcher.start()
                assertEquals(1, store.recoverCallCount.get())
                assertEquals(listOf(60_000L), store.recoverOlderThanMs.toList())
            }
        } finally {
            Files.deleteIfExists(Path.of(configPath))
        }
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
) : WebhookDeliveryStore {
    private var claimed = false
    private val recoveryLock = ReentrantLock()
    private val recoveryCondition = recoveryLock.newCondition()
    val retriedIds = CopyOnWriteArrayList<Long>()
    val retryReasons = CopyOnWriteArrayList<String>()
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
    ) {}

    override fun recoverExpiredClaims(olderThanMs: Long): Int {
        recoverOlderThanMs += olderThanMs
        recoverCallCount.incrementAndGet()
        recoveryLock.lock()
        try {
            recoveryCondition.signalAll()
        } finally {
            recoveryLock.unlock()
        }
        return 0
    }

    override fun close() {}

    fun awaitRecoveries(
        expectedCalls: Int,
        timeoutMs: Long,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        recoveryLock.lock()
        try {
            while (recoverCallCount.get() < expectedCalls) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0L) {
                    break
                }
                recoveryCondition.await(remaining, TimeUnit.MILLISECONDS)
            }
        } finally {
            recoveryLock.unlock()
        }
        return recoverCallCount.get() >= expectedCalls
    }
}
