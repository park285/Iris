package party.qwer.iris.bridge

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
