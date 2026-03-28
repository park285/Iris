package party.qwer.iris.delivery.webhook

import com.sun.net.httpserver.HttpServer
import party.qwer.iris.ConfigManager
import party.qwer.iris.DEFAULT_WEBHOOK_ROUTE
import party.qwer.iris.model.StoredWebhookOutboxEntry
import party.qwer.iris.model.WebhookOutboxStatus
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
            RecordingWebhookOutboxStore(
                claimedEntries =
                    listOf(
                        storedEntry(id = 1L, roomId = 1L, messageId = "msg-1"),
                        storedEntry(id = 2L, roomId = 1L, messageId = "msg-2"),
                        storedEntry(id = 3L, roomId = 1L, messageId = "msg-3"),
                    ),
                onRetry = { retryLatch.countDown() },
            )

        val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", port), 0).apply {
                createContext("/webhook/iris") { exchange ->
                    Thread.sleep(300)
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

        assertEquals(1, store.retriedIds.size)
        assertTrue(store.retriedIds.single() in setOf(2L, 3L))
        assertTrue(store.retryReasons.all { it.contains("partition queue saturated") })
    }

    private fun storedEntry(
        id: Long,
        roomId: Long,
        messageId: String,
    ): StoredWebhookOutboxEntry =
        StoredWebhookOutboxEntry(
            id = id,
            roomId = roomId,
            route = DEFAULT_WEBHOOK_ROUTE,
            messageId = messageId,
            payloadJson = """{"messageId":"$messageId"}""",
            attemptCount = 0,
            nextAttemptAt = 0L,
            status = WebhookOutboxStatus.SENDING,
            createdAt = 1L,
            updatedAt = 1L,
        )

    private fun reservePort(): Int =
        ServerSocket(0).use { socket ->
            socket.localPort
        }
}

private class RecordingWebhookOutboxStore(
    private val claimedEntries: List<StoredWebhookOutboxEntry>,
    private val onRetry: () -> Unit,
) : WebhookOutboxStore {
    private var claimed = false
    val retriedIds = CopyOnWriteArrayList<Long>()
    val retryReasons = CopyOnWriteArrayList<String>()

    override fun enqueue(entry: PendingWebhookOutboxEntry): Boolean = true

    override fun claimReady(
        nowEpochMs: Long,
        limit: Int,
    ): List<StoredWebhookOutboxEntry> {
        if (claimed) {
            return emptyList()
        }
        claimed = true
        return claimedEntries.take(limit)
    }

    override fun markSent(id: Long) {}

    override fun markRetry(
        id: Long,
        nextAttemptAt: Long,
        lastError: String?,
    ) {
        // 이 테스트는 파티션 포화 시 즉시 requeue 경로만 검증한다.
    }

    override fun requeueClaim(
        id: Long,
        nextAttemptAt: Long,
        lastError: String?,
    ) {
        retriedIds += id
        retryReasons += lastError.orEmpty()
        onRetry()
    }

    override fun markDead(
        id: Long,
        lastError: String?,
    ) {}

    override fun recoverInFlight(nowEpochMs: Long) {}

    override fun close() {}
}
