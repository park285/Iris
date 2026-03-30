package party.qwer.iris.delivery.webhook

import com.sun.net.httpserver.HttpServer
import party.qwer.iris.ConfigManager
import party.qwer.iris.DEFAULT_WEBHOOK_ROUTE
import party.qwer.iris.model.WebhookOutboxStatus
import party.qwer.iris.persistence.IrisDatabaseSchema
import party.qwer.iris.persistence.JdbcSqliteHelper
import party.qwer.iris.persistence.PendingWebhookDelivery
import party.qwer.iris.persistence.SqliteWebhookDeliveryStore
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebhookOutboxTest {
    @Test
    fun `file outbox recovers sending entries as retry on restart`() {
        val outboxFile = Files.createTempFile("iris-outbox", ".json").toFile()
        FileWebhookOutboxStore(outboxFile, clock = { 100L }).use { store ->
            store.enqueue(
                PendingWebhookOutboxEntry(
                    roomId = 1L,
                    route = DEFAULT_WEBHOOK_ROUTE,
                    messageId = "msg-1",
                    payloadJson = """{"ok":true}""",
                ),
            )
            val claimed = store.claimReady(nowEpochMs = 100L, limit = 10)
            assertEquals(1, claimed.size)
        }

        FileWebhookOutboxStore(outboxFile, clock = { 200L }).use { reopened ->
            reopened.recoverInFlight(200L)
            val claimed = reopened.claimReady(nowEpochMs = 200L, limit = 10)

            assertEquals(1, claimed.size)
            assertEquals(WebhookOutboxStatus.SENDING, claimed.single().status)
        }

        outboxFile.delete()
    }

    @Test
    fun `outbox dispatcher replays persisted delivery`() {
        val helper = JdbcSqliteHelper.inMemory()
        IrisDatabaseSchema.createWebhookOutboxTable(helper)
        val store = SqliteWebhookDeliveryStore(helper)
        store.enqueue(
            PendingWebhookDelivery(
                messageId = "msg-1",
                roomId = 1L,
                route = DEFAULT_WEBHOOK_ROUTE,
                payloadJson = """{"route":"$DEFAULT_WEBHOOK_ROUTE","messageId":"msg-1"}""",
            ),
        )

        val configPath = Files.createTempFile("iris-outbox-config", ".json").toFile().absolutePath
        val config = ConfigManager(configPath = configPath)
        val port = reservePort()
        config.setWebhookEndpoint(DEFAULT_WEBHOOK_ROUTE, "http://127.0.0.1:$port/webhook/iris")

        val latch = CountDownLatch(1)
        val requestCount = AtomicInteger(0)
        val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", port), 0).apply {
                createContext("/webhook/iris") { exchange ->
                    requestCount.incrementAndGet()
                    latch.countDown()
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
                pollIntervalMs = 50L,
            ).use { dispatcher ->
                dispatcher.start()
                assertTrue(latch.await(5, TimeUnit.SECONDS))
                assertEquals(1, requestCount.get())
            }
        } finally {
            server.stop(0)
            (server.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
            helper.close()
            val configFilePath =
                Path.of(configPath)
            Files.deleteIfExists(configFilePath)
        }
    }

    @Test
    fun `same room deliveries preserve order while different rooms can overlap`() {
        val configPath = Files.createTempFile("iris-outbox-order", ".json").toFile().absolutePath
        val config = ConfigManager(configPath = configPath)
        val port = reservePort()
        config.setWebhookEndpoint(DEFAULT_WEBHOOK_ROUTE, "http://127.0.0.1:$port/webhook/iris")
        val helper = JdbcSqliteHelper.inMemory()
        IrisDatabaseSchema.createWebhookOutboxTable(helper)
        val store = SqliteWebhookDeliveryStore(helper)
        store.enqueue(
            PendingWebhookDelivery(
                messageId = "msg-1",
                roomId = 1L,
                route = DEFAULT_WEBHOOK_ROUTE,
                payloadJson = """{"messageId":"msg-1"}""",
            ),
        )
        store.enqueue(
            PendingWebhookDelivery(
                messageId = "msg-2",
                roomId = 1L,
                route = DEFAULT_WEBHOOK_ROUTE,
                payloadJson = """{"messageId":"msg-2"}""",
            ),
        )
        store.enqueue(
            PendingWebhookDelivery(
                messageId = "msg-3",
                roomId = 2L,
                route = DEFAULT_WEBHOOK_ROUTE,
                payloadJson = """{"messageId":"msg-3"}""",
            ),
        )

        val requestOrder = CopyOnWriteArrayList<String>()
        val currentConcurrent = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)
        val latch = CountDownLatch(3)
        val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", port), 0).apply {
                createContext("/webhook/iris") { exchange ->
                    val messageId = exchange.requestHeaders.getFirst("X-Iris-Message-Id").orEmpty()
                    requestOrder += messageId
                    val current = currentConcurrent.incrementAndGet()
                    maxConcurrent.updateAndGet { previous -> maxOf(previous, current) }
                    CountDownLatch(1).await(150, TimeUnit.MILLISECONDS)
                    currentConcurrent.decrementAndGet()
                    latch.countDown()
                    exchange.sendResponseHeaders(204, -1)
                    exchange.close()
                }
                executor = Executors.newFixedThreadPool(3)
                start()
            }

        try {
            WebhookOutboxDispatcher(
                config = config,
                store = store,
                transportOverride = "http1",
                partitionCount = 2,
                pollIntervalMs = 50L,
            ).use { dispatcher ->
                dispatcher.start()
                assertTrue(latch.await(5, TimeUnit.SECONDS))
                assertTrue(requestOrder.indexOf("msg-1") < requestOrder.indexOf("msg-2"))
                assertTrue(maxConcurrent.get() > 1)
            }
        } finally {
            server.stop(0)
            (server.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
            helper.close()
            Files.deleteIfExists(
                java.nio.file.Path
                    .of(configPath),
            )
        }
    }

    private fun reservePort(): Int =
        ServerSocket(0).use { socket ->
            socket.localPort
        }
}
