package party.qwer.iris

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

object ImageBridgeProtocol {
    const val PROTOCOL_VERSION = 1
    const val SOCKET_NAME = "iris-image-bridge"
    const val ACTION_SEND_IMAGE = "send_image"
    const val ACTION_HEALTH = "health"
    const val STATUS_SENT = "sent"
    const val STATUS_FAILED = "failed"
    const val STATUS_OK = "ok"
    const val MAX_FRAME_SIZE = 1_048_576

    private val serializerJson =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
        }

    @Serializable
    data class ImageBridgeRequest(
        val action: String,
        val protocolVersion: Int? = null,
        val roomId: Long? = null,
        val imagePaths: List<String> = emptyList(),
        val threadId: Long? = null,
        val threadScope: Int? = null,
        val requestId: String? = null,
        val token: String? = null,
    )

    @Serializable
    data class ImageBridgeCheck(
        val name: String,
        val ok: Boolean,
        val detail: String? = null,
    )

    @Serializable
    data class ImageBridgeDiscoveryHook(
        val name: String,
        val installed: Boolean,
        val installError: String? = null,
        val invocationCount: Int = 0,
        val lastSeenEpochMs: Long? = null,
        val lastSummary: String? = null,
    )

    @Serializable
    data class ImageBridgeDiscovery(
        val installAttempted: Boolean = false,
        val hooks: List<ImageBridgeDiscoveryHook> = emptyList(),
    )

    @Serializable
    data class ImageBridgeResponse(
        val status: String,
        val error: String? = null,
        val running: Boolean? = null,
        val specReady: Boolean? = null,
        val checkedAtEpochMs: Long? = null,
        val restartCount: Int? = null,
        val lastCrashMessage: String? = null,
        val checks: List<ImageBridgeCheck> = emptyList(),
        val discovery: ImageBridgeDiscovery? = null,
    )

    fun writeFrame(
        output: OutputStream,
        request: ImageBridgeRequest,
    ) = writeFramePayload(output, serializerJson.encodeToString(request))

    fun writeFrame(
        output: OutputStream,
        response: ImageBridgeResponse,
    ) = writeFramePayload(output, serializerJson.encodeToString(response))

    fun readRequestFrame(input: InputStream): ImageBridgeRequest = serializerJson.decodeFromString(readFramePayload(input))

    fun readResponseFrame(input: InputStream): ImageBridgeResponse = serializerJson.decodeFromString(readFramePayload(input))

    fun buildSendImageRequest(
        roomId: Long,
        imagePaths: List<String>,
        threadId: Long?,
        threadScope: Int?,
        requestId: String? = null,
        token: String? = null,
    ): ImageBridgeRequest =
        ImageBridgeRequest(
            action = ACTION_SEND_IMAGE,
            protocolVersion = PROTOCOL_VERSION,
            roomId = roomId,
            imagePaths = imagePaths,
            threadId = threadId,
            threadScope = threadScope,
            requestId = requestId,
            token = token,
        )

    fun buildHealthRequest(token: String? = null): ImageBridgeRequest =
        ImageBridgeRequest(
            action = ACTION_HEALTH,
            protocolVersion = PROTOCOL_VERSION,
            token = token,
        )

    fun buildSuccessResponse(): ImageBridgeResponse = ImageBridgeResponse(status = STATUS_SENT)

    fun buildFailureResponse(error: String): ImageBridgeResponse =
        ImageBridgeResponse(
            status = STATUS_FAILED,
            error = error,
        )

    private fun writeFramePayload(
        output: OutputStream,
        payload: String,
    ) {
        val bytes = payload.toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..MAX_FRAME_SIZE) { "invalid frame size: ${bytes.size}" }
        val dos = DataOutputStream(output)
        dos.writeInt(bytes.size)
        dos.write(bytes)
        dos.flush()
    }

    private fun readFramePayload(input: InputStream): String {
        val dis = DataInputStream(input)
        val size = dis.readInt()
        require(size in 1..MAX_FRAME_SIZE) { "invalid frame size: $size" }
        val bytes = ByteArray(size)
        dis.readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }
}
