package party.qwer.iris

import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

internal object ImageBridgeProtocol {
    const val SOCKET_NAME = "iris-image-bridge"
    const val ACTION_SEND_IMAGE = "send_image"
    const val STATUS_SENT = "sent"
    const val STATUS_FAILED = "failed"
    const val MAX_FRAME_SIZE = 1_048_576

    fun writeFrame(
        output: OutputStream,
        json: JSONObject,
    ) {
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        val dos = DataOutputStream(output)
        dos.writeInt(bytes.size)
        dos.write(bytes)
        dos.flush()
    }

    fun readFrame(input: InputStream): JSONObject {
        val dis = DataInputStream(input)
        val size = dis.readInt()
        require(size in 1..MAX_FRAME_SIZE) { "invalid frame size: $size" }
        val bytes = ByteArray(size)
        dis.readFully(bytes)
        return JSONObject(String(bytes, Charsets.UTF_8))
    }

    fun buildSendImageRequest(
        roomId: Long,
        imagePaths: List<String>,
        threadId: Long?,
        threadScope: Int?,
    ): JSONObject =
        JSONObject().apply {
            put("action", ACTION_SEND_IMAGE)
            put("roomId", roomId)
            put("imagePaths", JSONArray(imagePaths))
            if (threadId != null) put("threadId", threadId)
            if (threadScope != null) put("threadScope", threadScope)
        }

    fun buildSuccessResponse(): JSONObject =
        JSONObject().apply {
            put("status", STATUS_SENT)
        }

    fun buildFailureResponse(error: String): JSONObject =
        JSONObject().apply {
            put("status", STATUS_FAILED)
            put("error", error)
        }
}
