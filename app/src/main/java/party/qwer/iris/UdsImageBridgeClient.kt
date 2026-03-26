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
        val request =
            ImageBridgeProtocol.buildSendImageRequest(
                roomId,
                imagePaths,
                threadId,
                threadScope,
            )
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
