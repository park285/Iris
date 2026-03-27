package party.qwer.iris

import android.net.LocalSocket
import android.net.LocalSocketAddress
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

internal data class ImageBridgeResult(
    val success: Boolean,
    val error: String? = null,
)

internal data class ImageBridgeHealthCheck(
    val name: String,
    val ok: Boolean,
    val detail: String? = null,
)

internal data class ImageBridgeDiscoveryHook(
    val name: String,
    val installed: Boolean,
    val installError: String? = null,
    val invocationCount: Int,
    val lastSeenEpochMs: Long? = null,
    val lastSummary: String? = null,
)

internal data class ImageBridgeHealthResult(
    val reachable: Boolean,
    val running: Boolean,
    val specReady: Boolean,
    val restartCount: Int,
    val lastCrashMessage: String? = null,
    val checks: List<ImageBridgeHealthCheck> = emptyList(),
    val discoveryInstallAttempted: Boolean = false,
    val discoveryHooks: List<ImageBridgeDiscoveryHook> = emptyList(),
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
    private val socketFactory: () -> BridgeSocket = { AndroidBridgeSocket() },
) {
    fun sendImage(
        roomId: Long,
        imagePaths: List<String>,
        threadId: Long?,
        threadScope: Int?,
    ): ImageBridgeResult {
        try {
            return parseSendResponse(
                exchange(
                    ImageBridgeProtocol.buildSendImageRequest(
                        roomId,
                        imagePaths,
                        threadId,
                        threadScope,
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
            parseHealthResponse(exchange(ImageBridgeProtocol.buildHealthRequest()))
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

    private fun exchange(request: JSONObject): JSONObject {
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

            return ImageBridgeProtocol.readFrame(socket.inputStream)
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun parseSendResponse(response: JSONObject): ImageBridgeResult {
        val status = response.optString("status", "")
        return if (status == ImageBridgeProtocol.STATUS_SENT) {
            ImageBridgeResult(success = true)
        } else {
            ImageBridgeResult(
                success = false,
                error = response.optString("error", "unknown bridge error"),
            )
        }
    }

    private fun parseHealthResponse(response: JSONObject): ImageBridgeHealthResult {
        val status = response.optString("status", "")
        if (status != ImageBridgeProtocol.STATUS_OK) {
            return ImageBridgeHealthResult(
                reachable = true,
                running = false,
                specReady = false,
                restartCount = 0,
                error = response.optString("error", "unknown bridge health error"),
            )
        }
        val checks =
            response
                .optJSONArray("checks")
                ?.let { array ->
                    List(array.length()) { index ->
                        val item = array.getJSONObject(index)
                        ImageBridgeHealthCheck(
                            name = item.getString("name"),
                            ok = item.getBoolean("ok"),
                            detail = item.optString("detail").ifBlank { null },
                        )
                    }
                }.orEmpty()
        return ImageBridgeHealthResult(
            reachable = true,
            running = response.optBoolean("running", false),
            specReady = response.optBoolean("specReady", false),
            restartCount = response.optInt("restartCount", 0),
            lastCrashMessage = response.optString("lastCrashMessage").ifBlank { null },
            checks = checks,
            discoveryInstallAttempted = response.optJSONObject("discovery")?.optBoolean("installAttempted", false) ?: false,
            discoveryHooks =
                response
                    .optJSONObject("discovery")
                    ?.optJSONArray("hooks")
                    ?.let { array ->
                        List(array.length()) { index ->
                            val item = array.getJSONObject(index)
                            ImageBridgeDiscoveryHook(
                                name = item.getString("name"),
                                installed = item.getBoolean("installed"),
                                installError = item.optString("installError").ifBlank { null },
                                invocationCount = item.optInt("invocationCount", 0),
                                lastSeenEpochMs =
                                    if (item.has("lastSeenEpochMs")) {
                                        item.getLong("lastSeenEpochMs")
                                    } else {
                                        null
                                    },
                                lastSummary = item.optString("lastSummary").ifBlank { null },
                            )
                        }
                    }.orEmpty(),
        )
    }
}
