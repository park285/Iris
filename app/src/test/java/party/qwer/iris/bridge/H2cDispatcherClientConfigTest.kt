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

    @Test
    fun `h2c transport sets maxRequests and maxRequestsPerHost to queue capacity`() {
        val dispatcher = okhttp3.Dispatcher()
        val pool = okhttp3.ConnectionPool()
        val config = party.qwer.iris.ConfigManager(configPath = "/tmp/iris-client-config-test.json")

        H2cDispatcher(config, transportOverride = "h2c").use { h2c ->
            val sharedDispatcher = readDispatcherField(h2c)
            kotlin.test.assertEquals(64, sharedDispatcher.maxRequests)
            kotlin.test.assertEquals(64, sharedDispatcher.maxRequestsPerHost)
        }
    }

    @Test
    fun `http1 transport keeps default maxRequests`() {
        val config = party.qwer.iris.ConfigManager(configPath = "/tmp/iris-client-config-test.json")

        H2cDispatcher(config, transportOverride = "http1").use { h2c ->
            val sharedDispatcher = readDispatcherField(h2c)
            kotlin.test.assertEquals(8, sharedDispatcher.maxRequests)
            kotlin.test.assertEquals(8, sharedDispatcher.maxRequestsPerHost)
        }
    }

    private fun readDispatcherField(h2cDispatcher: H2cDispatcher): okhttp3.Dispatcher =
        H2cDispatcher::class.java.getDeclaredField("sharedDispatcher").let { field ->
            field.isAccessible = true
            field.get(h2cDispatcher) as okhttp3.Dispatcher
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
