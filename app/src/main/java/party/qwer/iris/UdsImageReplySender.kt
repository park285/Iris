package party.qwer.iris

import android.net.Uri

internal class UdsImageReplySender(
    private val client: UdsImageBridgeClient = UdsImageBridgeClient(),
) : NativeImageReplySender {
    override fun send(
        roomId: Long,
        uris: List<Uri>,
        threadId: Long?,
        threadScope: Int?,
    ) {
        val paths =
            uris.map { uri ->
                uri.path
                    ?: error("image URI has no file path: $uri")
            }
        IrisLogger.info(
            "[UdsImageReplySender] sending ${paths.size} image(s) to bridge" +
                " room=$roomId threadId=$threadId scope=$threadScope",
        )
        val result = client.sendImage(roomId, paths, threadId, threadScope)
        if (!result.success) {
            error("image bridge send failed: ${result.error}")
        }
        IrisLogger.info("[UdsImageReplySender] bridge send completed room=$roomId")
    }
}
