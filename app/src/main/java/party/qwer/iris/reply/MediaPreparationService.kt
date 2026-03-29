package party.qwer.iris.reply

import party.qwer.iris.ImagePayloadMetadata
import party.qwer.iris.PreparedImages
import party.qwer.iris.cleanupPreparedImages
import party.qwer.iris.ensureImageDir
import party.qwer.iris.saveImage
import party.qwer.iris.validateImagePayloads
import java.io.File

internal class MediaPreparationService(
    private val imageDecoder: (String) -> ByteArray,
    private val mediaScanner: (File) -> Unit,
    private val imageDir: File,
    private val imageMediaScanEnabled: Boolean,
    private val maxImagesPerRequest: Int = 8,
    private val maxTotalBytes: Int = 30 * 1024 * 1024,
) {
    fun prepare(
        room: Long,
        payloadMetadata: List<ImagePayloadMetadata>,
    ): PreparedImages {
        val validatedPayloads =
            validateImagePayloads(
                payloadMetadata.map { it.base64 },
                imageDecoder = imageDecoder,
                maxImagesPerRequest = maxImagesPerRequest,
                maxTotalBytes = maxTotalBytes,
            )
        ensureImageDir(imageDir)

        val imagePaths = ArrayList<String>(validatedPayloads.size)
        val createdFiles = ArrayList<File>(validatedPayloads.size)

        try {
            validatedPayloads.forEach { payload ->
                val imageFile = saveImage(payload.bytes, imageDir)
                createdFiles.add(imageFile)
                if (imageMediaScanEnabled) {
                    mediaScanner(imageFile)
                }
                imagePaths.add(imageFile.absolutePath)
            }
            require(imagePaths.isNotEmpty()) { "no image paths created" }
        } catch (e: Exception) {
            createdFiles.forEach { file ->
                if (file.exists() && !file.delete()) {
                    party.qwer.iris.IrisLogger.error("Failed to delete partially prepared image file: ${file.absolutePath}")
                }
            }
            throw e
        }

        return PreparedImages(room = room, imagePaths = imagePaths, files = createdFiles)
    }

    fun cleanup(preparedImages: PreparedImages) {
        cleanupPreparedImages(preparedImages)
    }
}
