package party.qwer.iris

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path

internal interface ImagePayloadHandle : AutoCloseable {
    val sha256Hex: String
    val sizeBytes: Long

    fun openInputStream(): InputStream
}

internal interface VerifiedImagePayloadHandle : ImagePayloadHandle {
    val format: ImageFormat
    val contentType: String
}

internal data class ByteArrayVerifiedImagePayloadHandle(
    private val bytes: ByteArray,
    override val format: ImageFormat,
    override val contentType: String,
    override val sha256Hex: String = sha256Hex(bytes),
) : VerifiedImagePayloadHandle {
    override val sizeBytes: Long
        get() = bytes.size.toLong()

    override fun openInputStream(): InputStream = ByteArrayInputStream(bytes)

    override fun close() = Unit
}

internal data class SpillFileVerifiedImagePayloadHandle(
    private val path: Path,
    override val format: ImageFormat,
    override val contentType: String,
    override val sizeBytes: Long,
    override val sha256Hex: String,
) : VerifiedImagePayloadHandle {
    override fun openInputStream(): InputStream = Files.newInputStream(path)

    override fun close() {
        Files.deleteIfExists(path)
    }
}

internal data class VerifiedImageHandleStagingPolicy(
    val maxInMemoryBytes: Int = 64 * 1024,
    val spillDirectory: Path =
        java.nio.file.Paths
            .get(System.getProperty("java.io.tmpdir") ?: "."),
) {
    init {
        require(maxInMemoryBytes > 0) { "maxInMemoryBytes must be positive" }
    }
}

internal fun verifiedImageHandle(
    bytes: ByteArray,
    stagingPolicy: VerifiedImageHandleStagingPolicy = VerifiedImageHandleStagingPolicy(),
): VerifiedImagePayloadHandle {
    val format = requireKnownImageFormat(bytes)
    val contentType = contentTypeForImageFormat(format)
    if (bytes.size <= stagingPolicy.maxInMemoryBytes) {
        return ByteArrayVerifiedImagePayloadHandle(
            bytes = bytes,
            format = format,
            contentType = contentType,
        )
    }

    Files.createDirectories(stagingPolicy.spillDirectory)
    val path = Files.createTempFile(stagingPolicy.spillDirectory, "iris-verified-image-", ".tmp")
    return try {
        Files.write(path, bytes)
        SpillFileVerifiedImagePayloadHandle(
            path = path,
            format = format,
            contentType = contentType,
            sizeBytes = bytes.size.toLong(),
            sha256Hex = sha256Hex(bytes),
        )
    } catch (error: Exception) {
        Files.deleteIfExists(path)
        throw error
    }
}

internal fun verifyImagePayloadHandles(
    imageBytesList: List<ByteArray>,
    policy: ReplyImagePolicy = ReplyImagePolicy(),
    stagingPolicy: VerifiedImageHandleStagingPolicy = VerifiedImageHandleStagingPolicy(),
): List<VerifiedImagePayloadHandle> =
    validateImageBytesPayload(
        imageBytesList = imageBytesList,
        policy = policy,
    ).map { bytes ->
        verifiedImageHandle(
            bytes = bytes,
            stagingPolicy = stagingPolicy,
        )
    }
