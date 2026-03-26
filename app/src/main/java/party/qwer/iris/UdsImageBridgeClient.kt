package party.qwer.iris

import android.net.LocalSocket
import android.net.LocalSocketAddress
import org.json.JSONObject
import java.io.IOException

internal data class ImageBridgeResult(
    val success: Boolean,
    val error: String? = null,
)

internal class UdsImageBridgeClient(
    private val socketName: String = ImageBridgeProtocol.SOCKET_NAME,
    private val connectTimeoutMs: Int = 5_000,
    private val readTimeoutMs: Int = 30_000,
) {
    fun sendImage(
        roomId: Long,
        imagePaths: List<String>,
        threadId: Long?,
        threadScope: Int?,
    ): ImageBridgeResult {
        val request =
            ImageBridgeProtocol.buildSendImageRequest(
                roomId,
                imagePaths,
                threadId,
                threadScope,
            )
        val socket = LocalSocket()
        try {
            socket.connect(
                LocalSocketAddress(
                    socketName,
                    LocalSocketAddress.Namespace.ABSTRACT,
                ),
            )
            socket.soTimeout = readTimeoutMs

            ImageBridgeProtocol.writeFrame(socket.outputStream, request)
            socket.shutdownOutput()

            val response = ImageBridgeProtocol.readFrame(socket.inputStream)
            return parseResponse(response)
        } catch (e: IOException) {
            return ImageBridgeResult(
                success = false,
                error = "bridge connection failed: ${e.message}",
            )
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun parseResponse(response: JSONObject): ImageBridgeResult {
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
}
