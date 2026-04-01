package party.qwer.iris.delivery.webhook

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    fun `h2c transport sets maxRequestsPerHost to queue capacity and maxRequests higher for multi-domain`() {
        val config = party.qwer.iris.ConfigManager(configPath = "/tmp/iris-client-config-test.json")

        H2cDispatcher(config, transportOverride = "h2c").use { h2c ->
            val sharedDispatcher = readDispatcherField(h2c)
            kotlin.test.assertEquals(256, sharedDispatcher.maxRequests)
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

    @Test
    fun `all clients have explicit writeTimeout set`() {
        val sharedDispatcher = okhttp3.Dispatcher()
        val sharedConnectionPool = okhttp3.ConnectionPool()
        val factory = WebhookHttpClientFactory(WebhookTransport.H2C, sharedDispatcher, sharedConnectionPool)

        val h2cClient = readClientField(factory, "h2cClient")
        val http1Client = readClientField(factory, "http1Client")

        kotlin.test.assertEquals(
            WebhookHttpClientFactory.SOCKET_TIMEOUT_MS,
            h2cClient.writeTimeoutMillis.toLong(),
            "h2cClient writeTimeout",
        )
        kotlin.test.assertEquals(
            WebhookHttpClientFactory.SOCKET_TIMEOUT_MS,
            http1Client.writeTimeoutMillis.toLong(),
            "http1Client writeTimeout",
        )
    }

    @Test
    fun `transport security mode defaults to loopback cleartext only`() {
        assertEquals(
            TransportSecurityMode.LOOPBACK_HTTP_ALLOWED,
            resolveTransportSecurityMode(rawMode = null, allowCleartextHttp = false),
        )
        assertEquals(
            TransportSecurityMode.PRIVATE_OVERLAY_HTTP_ALLOWED,
            resolveTransportSecurityMode(rawMode = null, allowCleartextHttp = true),
        )
        assertEquals(
            TransportSecurityMode.TLS_REQUIRED,
            resolveTransportSecurityMode(rawMode = "tls_required", allowCleartextHttp = true),
        )
    }

    @Test
    fun `non loopback cleartext webhook is rejected in loopback mode`() {
        val factory =
            WebhookHttpClientFactory(
                transport = WebhookTransport.H2C,
                sharedDispatcher = okhttp3.Dispatcher(),
                sharedConnectionPool = okhttp3.ConnectionPool(),
                transportSecurityMode = TransportSecurityMode.LOOPBACK_HTTP_ALLOWED,
            )

        assertFailsWith<IllegalArgumentException> {
            factory.clientFor("http://8.8.8.8:30001/webhook/iris")
        }
    }

    @Test
    fun `private overlay cleartext webhook is allowed in loopback mode`() {
        val factory =
            WebhookHttpClientFactory(
                transport = WebhookTransport.H2C,
                sharedDispatcher = okhttp3.Dispatcher(),
                sharedConnectionPool = okhttp3.ConnectionPool(),
                transportSecurityMode = TransportSecurityMode.LOOPBACK_HTTP_ALLOWED,
            )

        val client = factory.clientFor("http://100.100.1.3:30001/webhook/iris")
        val h2cClient = readClientField(factory, "h2cClient")
        assertSame(h2cClient, client)
    }

    @Test
    fun `non loopback cleartext webhook is allowed in private overlay mode`() {
        val factory =
            WebhookHttpClientFactory(
                transport = WebhookTransport.H2C,
                sharedDispatcher = okhttp3.Dispatcher(),
                sharedConnectionPool = okhttp3.ConnectionPool(),
                transportSecurityMode = TransportSecurityMode.PRIVATE_OVERLAY_HTTP_ALLOWED,
            )

        val client = factory.clientFor("http://100.100.1.3:30001/webhook/iris")
        val h2cClient = readClientField(factory, "h2cClient")
        assertSame(h2cClient, client)
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
