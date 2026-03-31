package party.qwer.iris.reply

import party.qwer.iris.PreparedImages
import party.qwer.iris.ReplyImagePolicy
import party.qwer.iris.VerifiedImagePayloadHandle
import party.qwer.iris.cleanupPreparedImages
import party.qwer.iris.ensureImageDir
import party.qwer.iris.saveVerifiedStreamedImage
import party.qwer.iris.validateImagePayloadSizes
import java.io.File

internal class MediaPreparationService(
    private val mediaScanner: (File) -> Unit,
    private val imageDir: File,
    private val imageMediaScanEnabled: Boolean,
    private val imagePolicy: ReplyImagePolicy = ReplyImagePolicy(),
) : MediaPreparationCleanup {
    fun prepareVerifiedHandles(
        room: Long,
        imageHandles: List<VerifiedImagePayloadHandle>,
    ): PreparedImages {
        validateImagePayloadSizes(
            imageSizes = imageHandles.map { it.sizeBytes },
            policy = imagePolicy,
        )
        return prepareValidatedHandles(room = room, imageHandles = imageHandles)
    }

    private fun prepareValidatedHandles(
        room: Long,
        imageHandles: List<VerifiedImagePayloadHandle>,
    ): PreparedImages {
        ensureImageDir(imageDir)

        val imagePaths = ArrayList<String>(imageHandles.size)
        val createdFiles = ArrayList<File>(imageHandles.size)

        try {
            imageHandles.forEach { handle ->
                val imageFile =
                    handle.openInputStream().use { input ->
                        saveVerifiedStreamedImage(
                            input = input,
                            outputDir = imageDir,
                            expectedFormat = handle.format,
                        )
                    }
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
                    party.qwer.iris.IrisLogger
                        .error("Failed to delete partially prepared image file: ${file.absolutePath}")
                }
            }
            throw e
        }

        return PreparedImages(room = room, imagePaths = imagePaths, files = createdFiles)
    }

    override fun cleanup(preparedImages: PreparedImages) {
        cleanupPreparedImages(preparedImages)
    }
}
