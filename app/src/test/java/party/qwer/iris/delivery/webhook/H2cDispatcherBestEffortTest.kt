package party.qwer.iris.delivery.webhook

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import party.qwer.iris.ConfigManager
import party.qwer.iris.DEFAULT_WEBHOOK_ROUTE
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class H2cDispatcherBestEffortTest {
    private val config = ConfigManager(configPath = "/tmp/iris-h2c-dispatcher-best-effort-test-config.json")

    @Test
    fun `does not recover pending deliveries after restart`() {
        val port = reservePort()
        val endpoint = "http://127.0.0.1:$port/webhook/iris"
        config.setWebhookEndpoint(DEFAULT_WEBHOOK_ROUTE, endpoint)

        H2cDispatcher(config, transportOverride = "http1").use { dispatcher ->
            assertEquals(
                RoutingResult.ACCEPTED,
                dispatcher.route(
                    RoutingCommand(
                        text = "!ping",
                        room = "1",
                        sender = "tester",
                        userId = "1",
                        sourceLogId = 100L,
                    ),
                ),
            )
            Thread.sleep(300)
        }

        val requestCount = AtomicInteger(0)
        val latch = CountDownLatch(1)
        val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", port), 0).apply {
                createContext(
                    "/webhook/iris",
                    CountingHandler(requestCount, latch),
                )
                executor = Executors.newSingleThreadExecutor()
                start()
            }

        try {
            H2cDispatcher(config, transportOverride = "http1").use { restarted ->
                Thread.sleep(1_500)
                assertFalse(latch.await(200, TimeUnit.MILLISECONDS))
                assertEquals(0, requestCount.get())
                assertEquals(
                    RoutingResult.ACCEPTED,
                    restarted.route(
                        RoutingCommand(
                            text = "!ping",
                            room = "1",
                            sender = "tester",
                            userId = "1",
                            sourceLogId = 101L,
                        ),
                    ),
                )
                assertTrue(latch.await(3, TimeUnit.SECONDS))
                assertEquals(1, requestCount.get())
            }
        } finally {
            server.stop(0)
            (server.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
        }
    }

    @Test
    fun `returns retry later immediately when per-route queue is full`() {
        val port = reservePort()
        val endpoint = "http://127.0.0.1:$port/webhook/iris"
        config.setWebhookEndpoint(DEFAULT_WEBHOOK_ROUTE, endpoint)

        val firstRequestStarted = CountDownLatch(1)
        val releaseFirstRequest = CountDownLatch(1)
        val requestCount = AtomicInteger(0)
        val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", port), 0).apply {
                createContext(
                    "/webhook/iris",
                    BlockingHandler(requestCount, firstRequestStarted, releaseFirstRequest),
                )
                executor = Executors.newSingleThreadExecutor()
                start()
            }

        try {
            H2cDispatcher(
                config,
                transportOverride = "http1",
                queueCapacityOverride = 1,
                routeConcurrencyOverride = 1,
            ).use { dispatcher ->
                assertEquals(routeAccepted(dispatcher, 1L), RoutingResult.ACCEPTED)
                assertTrue(firstRequestStarted.await(3, TimeUnit.SECONDS))
                assertEquals(routeAccepted(dispatcher, 2L), RoutingResult.ACCEPTED)
                assertEquals(routeAccepted(dispatcher, 3L), RoutingResult.ACCEPTED)
                assertEquals(RoutingResult.RETRY_LATER, routeAccepted(dispatcher, 4L))
                releaseFirstRequest.countDown()
            }
        } finally {
            server.stop(0)
            (server.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
        }
    }

    @Test
    fun `drops exhausted delivery then continues with next queued message`() {
        val port = reservePort()
        val endpoint = "http://127.0.0.1:$port/webhook/iris"
        config.setWebhookEndpoint(DEFAULT_WEBHOOK_ROUTE, endpoint)

        val messageOrder = CopyOnWriteArrayList<String>()
        val requestLatch = CountDownLatch(4)
        val firstMessageId = "kakao-log-1-$DEFAULT_WEBHOOK_ROUTE"
        val secondMessageId = "kakao-log-2-$DEFAULT_WEBHOOK_ROUTE"
        val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", port), 0).apply {
                createContext(
                    "/webhook/iris",
                    SequencedResponseHandler(
                        firstMessageId = firstMessageId,
                        messageOrder = messageOrder,
                        requestLatch = requestLatch,
                    ),
                )
                executor = Executors.newSingleThreadExecutor()
                start()
            }

        try {
            H2cDispatcher(
                config,
                transportOverride = "http1",
                maxDeliveryAttemptsOverride = 3,
                routeConcurrencyOverride = 1,
                backoffDelayProviderOverride = { 10L },
            ).use { dispatcher ->
                assertEquals(RoutingResult.ACCEPTED, routeAccepted(dispatcher, 1L))
                assertEquals(RoutingResult.ACCEPTED, routeAccepted(dispatcher, 2L))

                assertTrue(requestLatch.await(10, TimeUnit.SECONDS))
                assertEquals(listOf(firstMessageId, secondMessageId, firstMessageId, firstMessageId), messageOrder)
            }
        } finally {
            server.stop(0)
            (server.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
        }
    }

    @Test
    fun `retry backoff does not block later same-route deliveries`() {
        val port = reservePort()
        val endpoint = "http://127.0.0.1:$port/webhook/iris"
        config.setWebhookEndpoint(DEFAULT_WEBHOOK_ROUTE, endpoint)

        val messageOrder = CopyOnWriteArrayList<String>()
        val requestLatch = CountDownLatch(3)
        val firstMessageId = "kakao-log-1-$DEFAULT_WEBHOOK_ROUTE"
        val secondMessageId = "kakao-log-2-$DEFAULT_WEBHOOK_ROUTE"
        val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", port), 0).apply {
                createContext(
                    "/webhook/iris",
                    RetryThenSuccessHandler(firstMessageId, messageOrder, requestLatch),
                )
                executor = Executors.newSingleThreadExecutor()
                start()
            }

        try {
            H2cDispatcher(
                config,
                transportOverride = "http1",
                maxDeliveryAttemptsOverride = 2,
                routeConcurrencyOverride = 1,
                backoffDelayProviderOverride = { 200L },
            ).use { dispatcher ->
                assertEquals(RoutingResult.ACCEPTED, routeAccepted(dispatcher, 1L))
                assertEquals(RoutingResult.ACCEPTED, routeAccepted(dispatcher, 2L))

                assertTrue(requestLatch.await(5, TimeUnit.SECONDS))
                assertEquals(listOf(firstMessageId, secondMessageId, firstMessageId), messageOrder)
            }
        } finally {
            server.stop(0)
            (server.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
        }
    }

    @Test
    fun `route concurrency bound is respected`() {
        val port = reservePort()
        val endpoint = "http://127.0.0.1:$port/webhook/iris"
        config.setWebhookEndpoint(DEFAULT_WEBHOOK_ROUTE, endpoint)

        val concurrentMax = AtomicInteger(0)
        val currentConcurrent = AtomicInteger(0)
        val allDone = CountDownLatch(6)
        val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", port), 0).apply {
                createContext("/webhook/iris") { exchange ->
                    val current = currentConcurrent.incrementAndGet()
                    concurrentMax.updateAndGet { max -> maxOf(max, current) }
                    Thread.sleep(200)
                    currentConcurrent.decrementAndGet()
                    allDone.countDown()
                    exchange.sendResponseHeaders(204, -1)
                    exchange.close()
                }
                executor = Executors.newFixedThreadPool(8)
                start()
            }

        try {
            H2cDispatcher(
                config,
                transportOverride = "http1",
                routeConcurrencyOverride = 2,
            ).use { dispatcher ->
                repeat(6) { i ->
                    assertEquals(
                        RoutingResult.ACCEPTED,
                        dispatcher.route(
                            RoutingCommand(
                                text = "!ping",
                                room = "${i + 1}",
                                sender = "tester",
                                userId = "${i + 1}",
                                sourceLogId = (i + 1).toLong(),
                            ),
                        ),
                    )
                }
                assertTrue(allDone.await(5, TimeUnit.SECONDS))
                assertTrue(concurrentMax.get() <= 2, "Expected at most 2 concurrent but max was ${concurrentMax.get()}")
            }
        } finally {
            server.stop(0)
            (server.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
        }
    }

    @Test
    fun `concurrent route processing sends multiple requests in parallel`() {
        val port = reservePort()
        val endpoint = "http://127.0.0.1:$port/webhook/iris"
        config.setWebhookEndpoint(DEFAULT_WEBHOOK_ROUTE, endpoint)

        val concurrentMax = AtomicInteger(0)
        val currentConcurrent = AtomicInteger(0)
        val allDone = CountDownLatch(4)
        val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", port), 0).apply {
                createContext("/webhook/iris") { exchange ->
                    val current = currentConcurrent.incrementAndGet()
                    concurrentMax.updateAndGet { max -> maxOf(max, current) }
                    Thread.sleep(200)
                    currentConcurrent.decrementAndGet()
                    allDone.countDown()
                    exchange.sendResponseHeaders(204, -1)
                    exchange.close()
                }
                executor = Executors.newFixedThreadPool(8)
                start()
            }

        try {
            H2cDispatcher(
                config,
                transportOverride = "http1",
                routeConcurrencyOverride = 4,
            ).use { dispatcher ->
                repeat(4) { i ->
                    assertEquals(
                        RoutingResult.ACCEPTED,
                        dispatcher.route(
                            RoutingCommand(
                                text = "!ping",
                                room = "${i + 1}",
                                sender = "tester",
                                userId = "${i + 1}",
                                sourceLogId = (i + 1).toLong(),
                            ),
                        ),
                    )
                }
                assertTrue(allDone.await(5, TimeUnit.SECONDS))
                assertTrue(concurrentMax.get() > 1, "Expected concurrent requests but max was ${concurrentMax.get()}")
            }
        } finally {
            server.stop(0)
            (server.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
        }
    }

    private fun routeAccepted(
        dispatcher: H2cDispatcher,
        sourceLogId: Long,
    ): RoutingResult =
        dispatcher.route(
            RoutingCommand(
                text = "!ping",
                room = "1",
                sender = "tester",
                userId = "1",
                sourceLogId = sourceLogId,
            ),
        )

    private fun reservePort(): Int =
        ServerSocket(0).use { socket ->
            socket.localPort
        }

    private class CountingHandler(
        private val requestCount: AtomicInteger,
        private val latch: CountDownLatch,
    ) : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            requestCount.incrementAndGet()
            latch.countDown()
            exchange.sendResponseHeaders(204, -1)
            exchange.close()
        }
    }

    private class BlockingHandler(
        private val requestCount: AtomicInteger,
        private val startedLatch: CountDownLatch,
        private val releaseLatch: CountDownLatch,
    ) : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            requestCount.incrementAndGet()
            startedLatch.countDown()
            releaseLatch.await(3, TimeUnit.SECONDS)
            exchange.sendResponseHeaders(204, -1)
            exchange.close()
        }
    }

    private class SequencedResponseHandler(
        private val firstMessageId: String,
        private val messageOrder: MutableList<String>,
        private val requestLatch: CountDownLatch,
    ) : HttpHandler {
        private val firstMessageAttempts = AtomicInteger(0)

        override fun handle(exchange: HttpExchange) {
            val messageId = exchange.requestHeaders.getFirst("X-Iris-Message-Id").orEmpty()
            messageOrder.add(messageId)
            val statusCode =
                if (messageId == firstMessageId && firstMessageAttempts.incrementAndGet() <= 3) {
                    500
                } else {
                    204
                }
            requestLatch.countDown()
            exchange.sendResponseHeaders(statusCode, -1)
            exchange.close()
        }
    }

    private class RetryThenSuccessHandler(
        private val firstMessageId: String,
        private val messageOrder: MutableList<String>,
        private val requestLatch: CountDownLatch,
    ) : HttpHandler {
        private val firstMessageAttempts = AtomicInteger(0)

        override fun handle(exchange: HttpExchange) {
            val messageId = exchange.requestHeaders.getFirst("X-Iris-Message-Id").orEmpty()
            messageOrder.add(messageId)
            val statusCode =
                if (messageId == firstMessageId && firstMessageAttempts.incrementAndGet() == 1) {
                    500
                } else {
                    204
                }
            requestLatch.countDown()
            exchange.sendResponseHeaders(statusCode, -1)
            exchange.close()
        }
    }
}
