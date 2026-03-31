package party.qwer.iris

import android.net.LocalSocket
import android.net.LocalSocketAddress
import party.qwer.iris.ImageBridgeProtocol
import party.qwer.iris.model.ImageBridgeDiscoveryHook
import party.qwer.iris.model.ImageBridgeHealthCheck
import party.qwer.iris.model.ImageBridgeHealthResult
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

internal data class ImageBridgeResult(
    val success: Boolean,
    val error: String? = null,
)

internal interface BridgeSocket : AutoCloseable {
    val inputStream: InputStream
    val outputStream: OutputStream

    @Throws(IOException::class)
    fun connect(
        socketName: String,
        timeoutMs: Int,
    )

    @Throws(IOException::class)
    fun connect(socketName: String)

    fun setReadTimeout(timeoutMs: Int)

    @Throws(IOException::class)
    fun shutdownOutput()
}

private class AndroidBridgeSocket : BridgeSocket {
    private val socket = LocalSocket()

    override val inputStream: InputStream
        get() = socket.inputStream

    override val outputStream: OutputStream
        get() = socket.outputStream

    override fun connect(
        socketName: String,
        timeoutMs: Int,
    ) {
        socket.connect(
            LocalSocketAddress(
                socketName,
                LocalSocketAddress.Namespace.ABSTRACT,
            ),
            timeoutMs,
        )
    }

    override fun connect(socketName: String) {
        socket.connect(
            LocalSocketAddress(
                socketName,
                LocalSocketAddress.Namespace.ABSTRACT,
            ),
        )
    }

    override fun setReadTimeout(timeoutMs: Int) {
        socket.soTimeout = timeoutMs
    }

    override fun shutdownOutput() {
        socket.shutdownOutput()
    }

    override fun close() {
        socket.close()
    }
}

internal class UdsImageBridgeClient(
    private val socketName: String = ImageBridgeProtocol.SOCKET_NAME,
    private val connectTimeoutMs: Int = 5_000,
    private val readTimeoutMs: Int = 30_000,
    private val bridgeToken: String = resolveBridgeToken(),
    private val securityModeRaw: String? = System.getenv("IRIS_BRIDGE_SECURITY_MODE"),
    private val socketFactory: () -> BridgeSocket = { AndroidBridgeSocket() },
) {
    init {
        if (bridgeToken.isBlank()) {
            if (isProductionSecurityMode(securityModeRaw)) {
                IrisLogger.error("[UdsImageBridgeClient] IRIS_BRIDGE_TOKEN must be configured in production mode")
            } else {
                IrisLogger.warn("[UdsImageBridgeClient] IRIS_BRIDGE_TOKEN is not configured; bridge requests will be unauthenticated in development mode")
            }
        }
    }

    fun sendImage(
        roomId: Long,
        imagePaths: List<String>,
        threadId: Long?,
        threadScope: Int?,
        requestId: String? = null,
    ): ImageBridgeResult {
        try {
            return parseSendResponse(
                exchange(
                    ImageBridgeProtocol.buildSendImageRequest(
                        roomId,
                        imagePaths,
                        threadId,
                        threadScope,
                        requestId,
                        bridgeToken,
                    ),
                ),
            )
        } catch (e: IOException) {
            return ImageBridgeResult(
                success = false,
                error = "bridge connection failed: ${e.message}",
            )
        } catch (e: RuntimeException) {
            return ImageBridgeResult(
                success = false,
                error = "bridge protocol failed: ${e.message}",
            )
        }
    }

    fun queryHealth(): ImageBridgeHealthResult =
        try {
            parseHealthResponse(exchange(ImageBridgeProtocol.buildHealthRequest(token = bridgeToken)))
        } catch (e: IOException) {
            ImageBridgeHealthResult(
                reachable = false,
                running = false,
                specReady = false,
                restartCount = 0,
                error = "bridge connection failed: ${e.message}",
            )
        } catch (e: RuntimeException) {
            ImageBridgeHealthResult(
                reachable = false,
                running = false,
                specReady = false,
                restartCount = 0,
                error = "bridge protocol failed: ${e.message}",
            )
        }

    private fun exchange(request: ImageBridgeProtocol.ImageBridgeRequest): ImageBridgeProtocol.ImageBridgeResponse {
        val socket = socketFactory()
        try {
            try {
                socket.connect(socketName, connectTimeoutMs)
            } catch (_: UnsupportedOperationException) {
                socket.connect(socketName)
            }
            socket.setReadTimeout(readTimeoutMs)

            ImageBridgeProtocol.writeFrame(socket.outputStream, request)
            socket.shutdownOutput()

            return ImageBridgeProtocol.readResponseFrame(socket.inputStream)
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun parseSendResponse(response: ImageBridgeProtocol.ImageBridgeResponse): ImageBridgeResult {
        val status = response.status
        return if (status == ImageBridgeProtocol.STATUS_SENT) {
            ImageBridgeResult(success = true)
        } else {
            ImageBridgeResult(
                success = false,
                error = response.error ?: "unknown bridge error",
            )
        }
    }

    private fun parseHealthResponse(response: ImageBridgeProtocol.ImageBridgeResponse): ImageBridgeHealthResult {
        val status = response.status
        if (status != ImageBridgeProtocol.STATUS_OK) {
            return ImageBridgeHealthResult(
                reachable = true,
                running = false,
                specReady = false,
                restartCount = 0,
                error = response.error ?: "unknown bridge health error",
            )
        }
        return ImageBridgeHealthResult(
            reachable = true,
            running = response.running ?: false,
            specReady = response.specReady ?: false,
            checkedAtEpochMs = response.checkedAtEpochMs,
            restartCount = response.restartCount ?: 0,
            lastCrashMessage = response.lastCrashMessage,
            checks =
                response.checks.map { item ->
                    ImageBridgeHealthCheck(
                        name = item.name,
                        ok = item.ok,
                        detail = item.detail,
                    )
                },
            discoveryInstallAttempted = response.discovery?.installAttempted ?: false,
            discoveryHooks =
                response
                    .discovery
                    ?.hooks
                    ?.map { item ->
                        ImageBridgeDiscoveryHook(
                            name = item.name,
                            installed = item.installed,
                            installError = item.installError,
                            invocationCount = item.invocationCount,
                            lastSeenEpochMs = item.lastSeenEpochMs,
                            lastSummary = item.lastSummary,
                        )
                    }.orEmpty(),
        )
    }

    private fun isProductionSecurityMode(raw: String?): Boolean =
        when (raw?.trim()?.lowercase()) {
            "development",
            "dev",
            -> false
            else -> true
        }
}
