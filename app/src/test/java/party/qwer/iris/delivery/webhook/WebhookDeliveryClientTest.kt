package party.qwer.iris.delivery.webhook

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WebhookDeliveryClientTest {
    @Test
    fun `returns response status code from async webhook delivery`() {
        val port = reservePort()
        val endpoint = "http://127.0.0.1:$port/webhook/iris"
        val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", port), 0).apply {
                createContext("/webhook/iris", StatusHandler(204))
                executor = Executors.newSingleThreadExecutor()
                start()
            }

        try {
            val deliveryClient = createDeliveryClient()
            val request =
                Request
                    .Builder()
                    .url(endpoint)
                    .post("{}".toRequestBody("application/json".toMediaType()))
                    .build()

            val statusCode =
                runBlocking {
                    deliveryClient.execute(request, endpoint)
                }

            assertEquals(204, statusCode)
        } finally {
            server.stop(0)
            (server.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
        }
    }

    @Test
    fun `propagates io exception from async webhook delivery`() {
        val port = reservePort()
        val endpoint = "http://127.0.0.1:$port/webhook/iris"
        val deliveryClient = createDeliveryClient()
        val request =
            Request
                .Builder()
                .url(endpoint)
                .post("{}".toRequestBody("application/json".toMediaType()))
                .build()

        assertFailsWith<IOException> {
            runBlocking {
                deliveryClient.execute(request, endpoint)
            }
        }
    }

    @Test
    fun `timeout cancellation closes underlying http call`() {
        val serverSocket = ServerSocket(0)
        val endpoint = "http://127.0.0.1:${serverSocket.localPort}/webhook/iris"
        val requestReceived = CountDownLatch(1)
        val clientClosed = CountDownLatch(1)
        val serverThread =
            Thread {
                serverSocket.use { server ->
                    val socket = server.accept()
                    socket.use { client ->
                        val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.US_ASCII))
                        var contentLength = 0
                        while (true) {
                            val line = reader.readLine() ?: return@use
                            if (line.isEmpty()) {
                                break
                            }
                            if (line.startsWith("Content-Length:", ignoreCase = true)) {
                                contentLength = line.substringAfter(':').trim().toInt()
                            }
                        }
                        if (contentLength > 0) {
                            val body = CharArray(contentLength)
                            var read = 0
                            while (read < contentLength) {
                                val count = reader.read(body, read, contentLength - read)
                                if (count < 0) {
                                    break
                                }
                                read += count
                            }
                        }
                        requestReceived.countDown()
                        try {
                            if (reader.read() < 0) {
                                clientClosed.countDown()
                            }
                        } catch (_: IOException) {
                            clientClosed.countDown()
                        }
                    }
                }
            }.apply {
                isDaemon = true
                start()
            }

        try {
            val deliveryClient = createDeliveryClient()
            val request =
                Request
                    .Builder()
                    .url(endpoint)
                    .post("{}".toRequestBody("application/json".toMediaType()))
                    .build()

            assertFailsWith<TimeoutCancellationException> {
                runBlocking {
                    withTimeout(200L) {
                        deliveryClient.execute(request, endpoint)
                    }
                }
            }

            assertTrue(requestReceived.await(5, TimeUnit.SECONDS), "server should receive request before cancellation")
            assertTrue(clientClosed.await(5, TimeUnit.SECONDS), "underlying call should close socket on cancellation")
        } finally {
            serverSocket.close()
            serverThread.join(5_000L)
        }
    }

    private fun createDeliveryClient(): WebhookDeliveryClient {
        val sharedDispatcher = Dispatcher()
        val sharedConnectionPool = ConnectionPool()
        val clientFactory = WebhookHttpClientFactory(WebhookTransport.HTTP1, sharedDispatcher, sharedConnectionPool)
        return WebhookDeliveryClient(clientFactory)
    }

    private fun reservePort(): Int =
        ServerSocket(0).use { socket ->
            socket.localPort
        }

    private class StatusHandler(
        private val statusCode: Int,
    ) : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            exchange.sendResponseHeaders(statusCode, -1)
            exchange.close()
        }
    }
}
