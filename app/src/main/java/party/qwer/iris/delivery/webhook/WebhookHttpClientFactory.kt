package party.qwer.iris.delivery.webhook

import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit

private const val ALLOW_CLEARTEXT_HTTP_ENV = "IRIS_ALLOW_CLEARTEXT_HTTP"
private const val TRANSPORT_SECURITY_MODE_ENV = "IRIS_WEBHOOK_TRANSPORT_SECURITY_MODE"

private fun cleartextFlagEnabled(rawValue: String?): Boolean =
    rawValue
        ?.trim()
        ?.lowercase()
        ?.let { normalized -> normalized == "1" || normalized == "true" || normalized == "on" }
        ?: false

internal enum class WebhookTransport {
    H2C,
    HTTP1,
}

internal enum class TransportSecurityMode {
    TLS_REQUIRED,
    LOOPBACK_HTTP_ALLOWED,
    PRIVATE_OVERLAY_HTTP_ALLOWED,
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

internal fun resolveTransportSecurityMode(
    rawMode: String?,
    allowCleartextHttp: Boolean = cleartextFlagEnabled(System.getenv(ALLOW_CLEARTEXT_HTTP_ENV)),
): TransportSecurityMode {
    val normalized = rawMode?.trim()?.lowercase().orEmpty()
    return when (normalized) {
        "tls_required", "tls", "https_only" -> TransportSecurityMode.TLS_REQUIRED
        "loopback_http_allowed", "loopback" -> TransportSecurityMode.LOOPBACK_HTTP_ALLOWED
        "private_overlay_http_allowed", "private_overlay" ->
            TransportSecurityMode.PRIVATE_OVERLAY_HTTP_ALLOWED

        else ->
            if (allowCleartextHttp) {
                TransportSecurityMode.PRIVATE_OVERLAY_HTTP_ALLOWED
            } else {
                TransportSecurityMode.LOOPBACK_HTTP_ALLOWED
            }
    }
}

internal class WebhookHttpClientFactory(
    transport: WebhookTransport,
    private val sharedDispatcher: Dispatcher,
    private val sharedConnectionPool: ConnectionPool,
    private val transportSecurityMode: TransportSecurityMode =
        resolveTransportSecurityMode(
            rawMode = System.getenv(TRANSPORT_SECURITY_MODE_ENV),
            allowCleartextHttp = cleartextFlagEnabled(System.getenv(ALLOW_CLEARTEXT_HTTP_ENV)),
        ),
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
        if (webhookUrl.startsWith("http://")) {
            when (transportSecurityMode) {
                TransportSecurityMode.TLS_REQUIRED ->
                    throw IllegalArgumentException("cleartext HTTP webhook is disabled by transport security mode")

                TransportSecurityMode.LOOPBACK_HTTP_ALLOWED -> {
                    if (!isLoopbackWebhookUrl(webhookUrl)) {
                        throw IllegalArgumentException(
                            "cleartext HTTP webhook requires loopback host or IRIS_WEBHOOK_TRANSPORT_SECURITY_MODE=PRIVATE_OVERLAY_HTTP_ALLOWED",
                        )
                    }
                }

                TransportSecurityMode.PRIVATE_OVERLAY_HTTP_ALLOWED -> {
                    if (!isTrustedPrivateWebhookUrl(webhookUrl)) {
                        throw IllegalArgumentException(
                            "cleartext HTTP webhook requires a trusted private-network host " +
                                "(loopback, RFC1918, CGNAT, or IPv6 ULA) when " +
                                "IRIS_WEBHOOK_TRANSPORT_SECURITY_MODE=PRIVATE_OVERLAY_HTTP_ALLOWED",
                        )
                    }
                }
            }
        }
        return defaultClient
    }

    fun shutdownSharedResources() {
        sharedDispatcher.executorService.shutdownNow()
        sharedConnectionPool.evictAll()
    }

    private fun isLoopbackWebhookUrl(webhookUrl: String): Boolean {
        val host = webhookUrl.toHttpUrlOrNull()?.host?.lowercase() ?: return false
        if (host == "localhost") {
            return true
        }
        val address =
            runCatching { InetAddress.getByName(host) }
                .getOrNull()
                ?: return false
        return address.isLoopbackAddress
    }

    private fun isTrustedPrivateWebhookUrl(webhookUrl: String): Boolean {
        val host = webhookUrl.toHttpUrlOrNull()?.host?.lowercase() ?: return false
        if (host == "localhost") {
            return true
        }
        val address =
            runCatching { InetAddress.getByName(host) }
                .getOrNull()
                ?: return false
        return when (address) {
            is Inet4Address -> isTrustedPrivateIpv4(address)
            is Inet6Address -> address.isLoopbackAddress || address.isSiteLocalAddress || isUniqueLocalIpv6(address)
            else -> false
        }
    }

    private fun isTrustedPrivateIpv4(address: Inet4Address): Boolean {
        val octets = address.address
        val first = octets[0].toInt() and 0xFF
        val second = octets[1].toInt() and 0xFF
        return when {
            address.isLoopbackAddress -> true
            first == 10 -> true
            first == 172 && second in 16..31 -> true
            first == 192 && second == 168 -> true
            first == 100 && second in 64..127 -> true
            else -> false
        }
    }

    private fun isUniqueLocalIpv6(address: Inet6Address): Boolean {
        val first = address.address[0].toInt() and 0xFF
        return (first and 0xFE) == 0xFC
    }

    companion object {
        internal const val CONNECT_TIMEOUT_MS = 10_000L
        internal const val REQUEST_TIMEOUT_MS = 30_000L
        internal const val SOCKET_TIMEOUT_MS = 30_000L
    }
}
