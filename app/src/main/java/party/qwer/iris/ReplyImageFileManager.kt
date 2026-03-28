package party.qwer.iris

import android.content.Intent
import android.net.Uri
import java.io.File

internal data class PreparedImages(
    val room: Long,
    val imagePaths: ArrayList<String>,
    val files: ArrayList<File>,
)

internal fun ensureImageDir(imageDir: File) {
    if (imageDir.exists()) {
        return
    }
    check(imageDir.mkdirs() || imageDir.exists()) {
        "Failed to create image directory: ${imageDir.absolutePath}"
    }
}

internal fun cleanupPreparedImages(preparedImages: PreparedImages) {
    preparedImages.files.forEach { file ->
        if (file.exists() && !file.delete()) {
            IrisLogger.error("Failed to delete prepared image file: ${file.absolutePath}")
        }
    }
}

// Android Q 이상에서 ACTION_MEDIA_SCANNER_SCAN_FILE은 deprecated됨.
// Context 없는 환경이므로 MediaScannerConnection 사용 불가.
// shell context에서 broadcast 방식을 유지한다.
@Suppress("DEPRECATION")
internal fun broadcastMediaScan(uri: Uri) {
    val mediaScanIntent =
        Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE).apply {
            data = uri
        }
    AndroidHiddenApi.broadcastIntent(mediaScanIntent)
}
