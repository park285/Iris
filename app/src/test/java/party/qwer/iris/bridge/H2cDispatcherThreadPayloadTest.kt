package party.qwer.iris.bridge

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import party.qwer.iris.Configurable
import party.qwer.iris.DEFAULT_WEBHOOK_ROUTE
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class H2cDispatcherThreadPayloadTest {
    @Test
    fun `includes thread metadata in webhook payload when routing command has thread metadata`() {
        val port = reservePort()
        val endpoint = "http://127.0.0.1:$port/webhook/iris"
        Configurable.setWebhookEndpoint(DEFAULT_WEBHOOK_ROUTE, endpoint)

        val requestBody = AtomicReference("")
        val requestLatch = CountDownLatch(1)
        val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", port), 0).apply {
                createContext(
                    "/webhook/iris",
                    CapturingHandler(requestBody, requestLatch),
                )
                executor = Executors.newSingleThreadExecutor()
                start()
            }

        try {
            H2cDispatcher(transportOverride = "http1").use { dispatcher ->
                assertEquals(
                    RoutingResult.ACCEPTED,
                    dispatcher.route(
                        RoutingCommand(
                            text = "!질문 hi",
                            room = "1",
                            sender = "tester",
                            userId = "1",
                            sourceLogId = 42L,
                            chatLogId = "3796822474849894401",
                            roomType = "OD",
                            roomLinkId = "435751742",
                            threadId = "12345",
                            threadScope = 2,
                            messageType = "1",
                            attachment = "{encrypted-data}",
                        ),
                    ),
                )

                assertTrue(requestLatch.await(3, TimeUnit.SECONDS))
                assertTrue(requestBody.get().contains(""""chatLogId":"3796822474849894401""""))
                assertTrue(requestBody.get().contains(""""roomType":"OD""""))
                assertTrue(requestBody.get().contains(""""roomLinkId":"435751742""""))
                assertTrue(requestBody.get().contains(""""threadId":"12345""""))
                assertTrue(requestBody.get().contains(""""threadScope":2"""))
                assertTrue(requestBody.get().contains(""""type":"1""""))
                assertTrue(requestBody.get().contains(""""attachment":"{encrypted-data}""""))
            }
        } finally {
            server.stop(0)
            (server.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
        }
    }

    @Test
    fun `omits type and attachment from payload when not provided`() {
        val port = reservePort()
        val endpoint = "http://127.0.0.1:$port/webhook/iris"
        Configurable.setWebhookEndpoint(DEFAULT_WEBHOOK_ROUTE, endpoint)

        val requestBody = AtomicReference("")
        val requestLatch = CountDownLatch(1)
        val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", port), 0).apply {
                createContext(
                    "/webhook/iris",
                    CapturingHandler(requestBody, requestLatch),
                )
                executor = Executors.newSingleThreadExecutor()
                start()
            }

        try {
            H2cDispatcher(transportOverride = "http1").use { dispatcher ->
                assertEquals(
                    RoutingResult.ACCEPTED,
                    dispatcher.route(
                        RoutingCommand(
                            text = "!질문 hi",
                            room = "1",
                            sender = "tester",
                            userId = "1",
                            sourceLogId = 43L,
                        ),
                    ),
                )

                assertTrue(requestLatch.await(3, TimeUnit.SECONDS))
                val body = requestBody.get()
                assertTrue(!body.contains(""""type":""""))
                assertTrue(!body.contains(""""attachment":""""))
            }
        } finally {
            server.stop(0)
            (server.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
        }
    }

    private fun reservePort(): Int =
        ServerSocket(0).use { socket ->
            socket.localPort
        }

    private class CapturingHandler(
        private val requestBody: AtomicReference<String>,
        private val requestLatch: CountDownLatch,
    ) : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            requestBody.set(exchange.requestBody.bufferedReader().use { it.readText() })
            requestLatch.countDown()
            exchange.sendResponseHeaders(204, -1)
            exchange.close()
        }
    }
}
