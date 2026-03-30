package party.qwer.iris.imagebridge.runtime

import android.os.Process
import java.io.File

internal class BridgePeerIdentityValidator(
    private val allowedUids: Set<Int>,
) {
    constructor(
        securityMode: BridgeSecurityMode = BridgeSecurityMode.fromEnv(),
        extraUidsRaw: String? = System.getenv("IRIS_BRIDGE_ALLOWED_UIDS"),
    ) : this(buildAllowedUids(securityMode, extraUidsRaw))

    fun validate(peerUid: Int?) {
        require(peerUid != null && peerUid in allowedUids) {
            "unauthorized bridge client uid=${peerUid ?: -1}"
        }
    }

    companion object {
        private fun buildAllowedUids(
            securityMode: BridgeSecurityMode,
            extraUidsRaw: String?,
        ): Set<Int> {
            val defaults =
                when (securityMode) {
                    BridgeSecurityMode.PRODUCTION -> linkedSetOf<Int>()
                    BridgeSecurityMode.DEVELOPMENT ->
                        linkedSetOf(
                            Process.ROOT_UID,
                            Process.SHELL_UID,
                        )
                }
            val configured =
                extraUidsRaw
                    ?.split(',')
                    ?.mapNotNull { token -> token.trim().toIntOrNull() }
                    ?.toSet()
                    .orEmpty()
            return defaults + configured
        }

        internal fun defaultAllowedUids(raw: String?): Set<Int> = buildAllowedUids(BridgeSecurityMode.DEVELOPMENT, raw)
    }
}

internal class BridgeImagePathValidator(
    rootPath: String = DEFAULT_ALLOWED_IMAGE_ROOT,
) {
    private val allowedRoot = File(rootPath).canonicalFile

    fun validate(imagePaths: List<String>): List<String> {
        require(imagePaths.isNotEmpty()) { "no image paths" }
        return imagePaths.map { path ->
            val imageFile = File(path).canonicalFile
            require(imageFile.isFile) { "image file not found: $path" }
            require(imageFile.path.startsWith("${allowedRoot.path}${File.separator}")) {
                "image path is outside allowed root: $path"
            }
            imageFile.path
        }
    }

    companion object {
        private const val DEFAULT_ALLOWED_IMAGE_ROOT = "/sdcard/Android/data/com.kakao.talk/files/iris-outbox-images"
    }
}
