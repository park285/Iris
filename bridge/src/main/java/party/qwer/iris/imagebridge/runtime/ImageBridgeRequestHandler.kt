package party.qwer.iris.imagebridge.runtime

import android.util.Log
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
    private val handshakeValidator: BridgeHandshakeValidator = BridgeHandshakeValidator(),
    private val serialExecutor: RoomThreadSerialExecutor = RoomThreadSerialExecutor(),
    private val pathValidator: BridgeImagePathValidator = BridgeImagePathValidator(),
    private val logError: (String, String, Throwable) -> Unit = { tag, message, error -> Log.e(tag, message, error) },
) {
    fun handle(request: ImageBridgeProtocol.ImageBridgeRequest): ImageBridgeProtocol.ImageBridgeResponse =
        try {
            handshakeValidator.validate(request)
            when (val action = request.action) {
                ImageBridgeProtocol.ACTION_SEND_IMAGE -> handleSendImage(request)
                ImageBridgeProtocol.ACTION_HEALTH -> healthProvider().toProtocolResponse()
                else -> failureResponse("unknown action: $action")
            }
        } catch (e: Exception) {
            logFailure(request, e)
            failureResponse(e.message ?: "internal error")
        }

    private fun handleSendImage(request: ImageBridgeProtocol.ImageBridgeRequest): ImageBridgeProtocol.ImageBridgeResponse {
        val health = healthProvider()
        check(health.specStatus.ready) {
            "bridge spec not ready"
        }
        val imageRequest =
            ImageSendRequest(
                roomId = checkNotNull(request.roomId) { "roomId missing" },
                imagePaths = pathValidator.validate(request.imagePaths),
                threadId = request.threadId,
                threadScope = request.threadScope,
                requestId = request.requestId,
            )
        health.discoverySnapshot.sendBlockReason(
            imageCount = imageRequest.imagePaths.size,
            threadId = imageRequest.threadId,
            threadScope = imageRequest.threadScope,
        )?.let { reason ->
            error(reason)
        }
        serialExecutor.execute(imageRequest.roomId, imageRequest.threadId) {
            imageSender(imageRequest)
        }
        return successResponse()
    }

    private fun logFailure(
        request: ImageBridgeProtocol.ImageBridgeRequest,
        error: Exception,
    ) {
        val action = request.action.ifBlank { "<missing>" }
        val roomId = request.roomId?.toString() ?: "<missing>"
        val requestId = request.requestId ?: "<missing>"
        runCatching {
            logError(TAG, "request handling failed action=$action roomId=$roomId requestId=$requestId", error)
        }
    }

    companion object {
        private const val TAG = "IrisBridge"

        fun successResponse(): ImageBridgeProtocol.ImageBridgeResponse = ImageBridgeProtocol.buildSuccessResponse()

        fun failureResponse(error: String): ImageBridgeProtocol.ImageBridgeResponse = ImageBridgeProtocol.buildFailureResponse(error)
    }
}
