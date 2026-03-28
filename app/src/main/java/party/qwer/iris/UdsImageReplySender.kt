package party.qwer.iris

internal class UdsImageReplySender(
    private val client: UdsImageBridgeClient = UdsImageBridgeClient(),
) : NativeImageReplySender {
    override fun send(
        roomId: Long,
        imagePaths: List<String>,
        threadId: Long?,
        threadScope: Int?,
        requestId: String?,
    ) {
        IrisLogger.info(
            "[UdsImageReplySender] sending ${imagePaths.size} image(s) to bridge" +
                " room=$roomId threadId=$threadId scope=$threadScope requestId=$requestId",
        )
        val result = client.sendImage(roomId, imagePaths, threadId, threadScope, requestId)
        if (!result.success) {
            error("image bridge send failed: ${result.error}")
        }
        IrisLogger.info("[UdsImageReplySender] bridge send completed room=$roomId requestId=$requestId")
    }
}
