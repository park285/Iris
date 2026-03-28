package party.qwer.iris.delivery.webhook

import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.util.concurrent.TimeUnit

internal enum class WebhookTransport {
    H2C,
    HTTP1,
}

internal fun resolveWebhookTransport(transportOverride: String?): WebhookTransport {
    val raw =
        transportOverride?.trim()?.lowercase()
            ?: System
                .getenv("IRIS_WEBHOOK_TRANSPORT")
                ?.trim()
                ?.lowercase()
                .orEmpty()
    return when (raw) {
        "http1", "http1_1", "http", "https" -> WebhookTransport.HTTP1
        else -> WebhookTransport.H2C
    }
}

internal class WebhookHttpClientFactory(
    transport: WebhookTransport,
    sharedDispatcher: Dispatcher,
    sharedConnectionPool: ConnectionPool,
) {
    private val baseClient: OkHttpClient =
        OkHttpClient
            .Builder()
            .retryOnConnectionFailure(false)
            .dispatcher(sharedDispatcher)
            .connectionPool(sharedConnectionPool)
            .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(SOCKET_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(SOCKET_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()

    private val h2cClient: OkHttpClient = baseClient.newBuilder().protocols(listOf(Protocol.H2_PRIOR_KNOWLEDGE)).build()
    private val http1Client: OkHttpClient = baseClient.newBuilder().protocols(listOf(Protocol.HTTP_1_1)).build()

    private val defaultClient: OkHttpClient =
        when (transport) {
            WebhookTransport.H2C -> h2cClient
            WebhookTransport.HTTP1 -> http1Client
        }

    fun clientFor(webhookUrl: String): OkHttpClient {
        if (webhookUrl.startsWith("https://")) {
            return http1Client
        }
        return defaultClient
    }

    companion object {
        internal const val CONNECT_TIMEOUT_MS = 10_000L
        internal const val REQUEST_TIMEOUT_MS = 30_000L
        internal const val SOCKET_TIMEOUT_MS = 30_000L
    }
}
