package party.qwer.iris

import android.content.Intent
import android.net.Uri
import java.io.File

internal data class ReplyRuntime(
    val replyService: ReplyService,
)

internal object ReplyRuntimeFactory {
    fun create(
        config: ReplyDispatchConfigProvider,
        bridgeClient: UdsImageBridgeClient,
        imagePolicy: ReplyImagePolicy = ReplyImagePolicy(),
        imageDir: File = File(IRIS_IMAGE_DIR_PATH),
        startService: (Intent) -> Unit = { intent -> AndroidHiddenApi.startService(intent) },
        startActivityAs: (String, Intent) -> Unit = { callerPackage, intent ->
            AndroidHiddenApi.startActivityAs(callerPackage, intent)
        },
    ): ReplyRuntime =
        ReplyRuntime(
            replyService =
                ReplyService(
                    config = config,
                    nativeImageReplySender = UdsImageReplySender(bridgeClient),
                    startService = startService,
                    startActivityAs = startActivityAs,
                    mediaScanner = { file -> broadcastMediaScan(Uri.fromFile(file)) },
                    imageDir = imageDir,
                    imagePolicy = imagePolicy,
                ),
        )
}
