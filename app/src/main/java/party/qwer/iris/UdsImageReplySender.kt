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
        requestId: String?,
    ) {
        val paths = uris.map(::requireFilePath)
        IrisLogger.info(
            "[UdsImageReplySender] sending ${paths.size} image(s) to bridge" +
                " room=$roomId threadId=$threadId scope=$threadScope requestId=$requestId",
        )
        val result = client.sendImage(roomId, paths, threadId, threadScope, requestId)
        if (!result.success) {
            error("image bridge send failed: ${result.error}")
        }
        IrisLogger.info("[UdsImageReplySender] bridge send completed room=$roomId requestId=$requestId")
    }

    private fun requireFilePath(uri: Uri): String =
        requireNotNull(uri.path?.takeIf { uri.scheme.equals("file", ignoreCase = true) }) {
            "unsupported image URI, only file:// URIs are allowed: $uri"
        }
}
