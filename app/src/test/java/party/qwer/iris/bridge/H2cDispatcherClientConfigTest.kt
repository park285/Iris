package party.qwer.iris.bridge

import kotlin.test.Test
import kotlin.test.assertSame

class H2cDispatcherClientConfigTest {
    @Test
    fun `shares dispatcher and connection pool across transport clients`() {
        val sharedDispatcher = okhttp3.Dispatcher()
        val sharedConnectionPool = okhttp3.ConnectionPool()
        val factory = WebhookHttpClientFactory(WebhookTransport.HTTP1, sharedDispatcher, sharedConnectionPool)

        val h2cClient = readClientField(factory, "h2cClient")
        val http1Client = readClientField(factory, "http1Client")

        assertSame(h2cClient.dispatcher, http1Client.dispatcher)
        assertSame(h2cClient.connectionPool, http1Client.connectionPool)
    }

    private fun readClientField(
        factory: WebhookHttpClientFactory,
        fieldName: String,
    ): okhttp3.OkHttpClient =
        WebhookHttpClientFactory::class.java.getDeclaredField(fieldName).let { field ->
            field.isAccessible = true
            field.get(factory) as okhttp3.OkHttpClient
        }
}
