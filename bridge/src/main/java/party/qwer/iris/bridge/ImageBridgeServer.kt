package party.qwer.iris.bridge

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.util.Log
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

internal object ImageBridgeServer {
    private const val TAG = "IrisBridge"
    private const val SOCKET_NAME = "iris-image-bridge"
    private const val MAX_FRAME_SIZE = 1_048_576

    private val running = AtomicBoolean(false)

    @Volatile
    private var sender: KakaoImageSender? = null

    fun start(
        context: Context,
        classLoader: ClassLoader,
    ) {
        if (!running.compareAndSet(false, true)) {
            Log.w(TAG, "bridge server already running")
            return
        }
        sender = KakaoImageSender(context, classLoader)
        Thread(
            {
                try {
                    serve()
                } catch (e: Exception) {
                    Log.e(TAG, "bridge server crashed", e)
                    running.set(false)
                }
            },
            "iris-bridge-server",
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun serve() {
        val serverSocket = LocalServerSocket(SOCKET_NAME)
        try {
            Log.i(TAG, "bridge server listening on @$SOCKET_NAME")
            while (running.get()) {
                val client = serverSocket.accept()
                handleClient(client)
            }
        } finally {
            runCatching { serverSocket.close() }
            Log.i(TAG, "bridge server socket closed")
        }
    }

    private fun handleClient(client: LocalSocket) {
        try {
            val request = readFrame(client.inputStream)
            val action = request.optString("action", "")
            val response =
                when (action) {
                    "send_image" -> handleSendImage(request)
                    else -> failureResponse("unknown action: $action")
                }
            writeFrame(client.outputStream, response)
        } catch (e: Exception) {
            Log.e(TAG, "client handler error", e)
            runCatching {
                writeFrame(client.outputStream, failureResponse(e.message ?: "internal error"))
            }
        } finally {
            runCatching { client.close() }
        }
    }

    private fun handleSendImage(request: JSONObject): JSONObject {
        val roomId = request.getLong("roomId")
        val pathsArray = request.getJSONArray("imagePaths")
        val paths = (0 until pathsArray.length()).map { pathsArray.getString(it) }
        val threadId = if (request.has("threadId")) request.getLong("threadId") else null
        val threadScope = if (request.has("threadScope")) request.getInt("threadScope") else null

        val imageSender = sender ?: return failureResponse("sender not initialized")
        return try {
            imageSender.send(roomId, paths, threadId, threadScope)
            JSONObject().apply { put("status", "sent") }
        } catch (e: Exception) {
            Log.e(TAG, "send failed room=$roomId", e)
            failureResponse(e.message ?: "send failed")
        }
    }

    private fun writeFrame(
        output: OutputStream,
        json: JSONObject,
    ) {
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        DataOutputStream(output).apply {
            writeInt(bytes.size)
            write(bytes)
            flush()
        }
    }

    private fun readFrame(input: InputStream): JSONObject {
        val dis = DataInputStream(input)
        val size = dis.readInt()
        require(size in 1..MAX_FRAME_SIZE) { "invalid frame size: $size" }
        val bytes = ByteArray(size)
        dis.readFully(bytes)
        return JSONObject(String(bytes, Charsets.UTF_8))
    }

    private fun failureResponse(error: String) =
        JSONObject().apply {
            put("status", "failed")
            put("error", error)
        }
}
