package party.qwer.iris

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.UUID

private val base64MimeDecoder = Base64.getMimeDecoder()
internal const val MAX_IMAGE_PAYLOAD_BYTES = 20 * 1024 * 1024
internal const val MAX_BASE64_IMAGE_PAYLOAD_LENGTH = MAX_IMAGE_PAYLOAD_BYTES * 4 / 3 + 4

internal fun decodeBase64Image(base64ImageDataString: String): ByteArray = base64MimeDecoder.decode(base64ImageDataString)

internal fun saveImage(
    imageBytes: ByteArray,
    outputDir: File,
): File {
    val extension = detectImageFileExtension(imageBytes)
    val tempFile = File(outputDir, "${UUID.randomUUID()}.$extension.tmp")
    val targetFile = File(outputDir, "${UUID.randomUUID()}.$extension")
    try {
        FileOutputStream(tempFile).use { output ->
            output.write(imageBytes)
            output.fd.sync()
        }
        Files.move(
            tempFile.toPath(),
            targetFile.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
        )
        return targetFile
    } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
        Files.move(
            tempFile.toPath(),
            targetFile.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
        )
        return targetFile
    } catch (error: Exception) {
        tempFile.delete()
        throw error
    }
}

internal fun detectImageFileExtension(imageBytes: ByteArray): String {
    if (isPngSignature(imageBytes)) {
        return "png"
    }

    if (isJpegSignature(imageBytes)) {
        return "jpg"
    }

    if (isWebpSignature(imageBytes)) {
        return "webp"
    }

    if (isGifSignature(imageBytes)) {
        return "gif"
    }

    return "img"
}

private fun isPngSignature(imageBytes: ByteArray): Boolean =
    imageBytes.size >= 8 &&
        imageBytes[0] == 0x89.toByte() &&
        imageBytes[1] == 0x50.toByte() &&
        imageBytes[2] == 0x4E.toByte() &&
        imageBytes[3] == 0x47.toByte()

private fun isJpegSignature(imageBytes: ByteArray): Boolean =
    imageBytes.size >= 3 &&
        imageBytes[0] == 0xFF.toByte() &&
        imageBytes[1] == 0xD8.toByte() &&
        imageBytes[2] == 0xFF.toByte()

private fun isWebpSignature(imageBytes: ByteArray): Boolean =
    imageBytes.size >= 12 &&
        imageBytes[0] == 0x52.toByte() &&
        imageBytes[1] == 0x49.toByte() &&
        imageBytes[2] == 0x46.toByte() &&
        imageBytes[3] == 0x46.toByte() &&
        imageBytes[8] == 0x57.toByte() &&
        imageBytes[9] == 0x45.toByte() &&
        imageBytes[10] == 0x42.toByte() &&
        imageBytes[11] == 0x50.toByte()

private fun isGifSignature(imageBytes: ByteArray): Boolean =
    imageBytes.size >= 4 &&
        imageBytes[0] == 0x47.toByte() &&
        imageBytes[1] == 0x49.toByte() &&
        imageBytes[2] == 0x46.toByte() &&
        imageBytes[3] == 0x38.toByte()
