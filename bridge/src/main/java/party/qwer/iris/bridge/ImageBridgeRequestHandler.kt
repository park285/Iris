package party.qwer.iris.bridge

import android.util.Log
import org.json.JSONObject
import party.qwer.iris.ImageBridgeProtocol

internal data class ImageSendRequest(
    val roomId: Long,
    val imagePaths: List<String>,
    val threadId: Long?,
    val threadScope: Int?,
    val requestId: String?,
)

internal class ImageBridgeRequestHandler(
    private val imageSender: (ImageSendRequest) -> Unit,
    private val healthProvider: () -> ImageBridgeHealthSnapshot,
    private val serialExecutor: RoomThreadSerialExecutor = RoomThreadSerialExecutor(),
    private val pathValidator: BridgeImagePathValidator = BridgeImagePathValidator(),
    private val logError: (String, String, Throwable) -> Unit = { tag, message, error -> Log.e(tag, message, error) },
) {
    fun handle(request: JSONObject): JSONObject =
        try {
            when (val action = request.optString("action", "")) {
                ImageBridgeProtocol.ACTION_SEND_IMAGE -> handleSendImage(request)
                ImageBridgeProtocol.ACTION_HEALTH -> healthProvider().toJson()
                else -> failureResponse("unknown action: $action")
            }
        } catch (e: Exception) {
            logFailure(request, e)
            failureResponse(e.message ?: "internal error")
        }

    private fun handleSendImage(request: JSONObject): JSONObject {
        val health = healthProvider()
        check(health.specStatus.ready) {
            "bridge spec not ready"
        }
        val imageRequest =
            ImageSendRequest(
                roomId = request.getLong("roomId"),
                imagePaths =
                    pathValidator.validate(
                        request.getJSONArray("imagePaths").let { paths ->
                            (0 until paths.length()).map(paths::getString)
                        },
                    ),
                threadId = request.optLongOrNull("threadId"),
                threadScope = request.optIntOrNull("threadScope"),
                requestId = request.optString("requestId").ifBlank { null },
            )
        health.discoverySnapshot.sendBlockReason(imageRequest.imagePaths.size)?.let { reason ->
            error(reason)
        }
        serialExecutor.execute(imageRequest.roomId, imageRequest.threadId) {
            imageSender(imageRequest)
        }
        return successResponse()
    }

    private fun JSONObject.optLongOrNull(name: String): Long? = if (has(name)) getLong(name) else null

    private fun JSONObject.optIntOrNull(name: String): Int? = if (has(name)) getInt(name) else null

    private fun logFailure(
        request: JSONObject,
        error: Exception,
    ) {
        val action = request.optString("action", "<missing>")
        val roomId = request.opt("roomId")?.toString() ?: "<missing>"
        val requestId = request.optString("requestId").ifBlank { "<missing>" }
        runCatching {
            logError(TAG, "request handling failed action=$action roomId=$roomId requestId=$requestId", error)
        }
    }

    companion object {
        private const val TAG = "IrisBridge"

        fun successResponse(): JSONObject =
            JSONObject().apply {
                put("status", ImageBridgeProtocol.STATUS_SENT)
            }

        fun failureResponse(error: String): JSONObject =
            JSONObject().apply {
                put("status", ImageBridgeProtocol.STATUS_FAILED)
                put("error", error)
            }
    }
}
