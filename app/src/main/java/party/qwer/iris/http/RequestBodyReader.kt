package party.qwer.iris.http

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import party.qwer.iris.IrisLogger
import party.qwer.iris.requestRejected
import party.qwer.iris.config.ConfigPathPolicy
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

private const val STREAM_BUFFER_BYTES = 8 * 1024
private const val MAX_IN_MEMORY_BODY_BYTES = 64 * 1024
private const val REQUEST_BODY_TEMP_FILE_PREFIX = "iris-request-body-"
private const val REQUEST_BODY_TEMP_FILE_SUFFIX = ".tmp"

internal suspend fun readProtectedBody(
    call: ApplicationCall,
    maxBodyBytes: Int,
): RequestBodyHandle =
    readBodyWithStreamingDigest(
        bodyChannel = call.receiveChannel(),
        declaredContentLength = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull(),
        maxBodyBytes = maxBodyBytes,
        bufferingPolicy = RequestBodyBufferingPolicy.fromEnv(),
    )

internal suspend fun readBodyWithStreamingDigest(
    bodyChannel: ByteReadChannel,
    declaredContentLength: Long?,
    maxBodyBytes: Int,
    bufferingPolicy: RequestBodyBufferingPolicy = RequestBodyBufferingPolicy.default(),
): RequestBodyHandle {
    if (declaredContentLength != null && declaredContentLength < 0) {
        requestRejected("invalid content length", HttpStatusCode.BadRequest)
    }
    if (declaredContentLength != null && declaredContentLength > maxBodyBytes.toLong()) {
        requestRejected("request body too large", HttpStatusCode.PayloadTooLarge)
    }

    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(STREAM_BUFFER_BYTES)
    var totalRead = 0L

    val requestBodySink = RequestBodySink(policy = bufferingPolicy)
    try {
        while (true) {
            val read = bodyChannel.readAvailable(buffer, 0, buffer.size)
            if (read == -1) {
                break
            }
            totalRead = accumulateReadBytes(totalRead, read.toLong(), maxBodyBytes.toLong())
            digest.update(buffer, 0, read)
            requestBodySink.write(buffer, 0, read)
        }

        val hexDigest = digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        return requestBodySink.detachHandle(
            sha256Hex = hexDigest,
            sizeBytes = totalRead,
        )
    } catch (error: Throwable) {
        requestBodySink.close()
        throw error
    }
}

internal fun readInputStreamWithStreamingDigest(
    input: InputStream,
    declaredContentLength: Long?,
    maxBodyBytes: Int,
    bufferingPolicy: RequestBodyBufferingPolicy = RequestBodyBufferingPolicy.default(),
): RequestBodyHandle {
    if (declaredContentLength != null && declaredContentLength < 0) {
        requestRejected("invalid content length", HttpStatusCode.BadRequest)
    }
    if (declaredContentLength != null && declaredContentLength > maxBodyBytes.toLong()) {
        requestRejected("request body too large", HttpStatusCode.PayloadTooLarge)
    }

    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(STREAM_BUFFER_BYTES)
    var totalRead = 0L

    val requestBodySink = RequestBodySink(policy = bufferingPolicy)
    try {
        while (true) {
            val read = input.read(buffer)
            if (read < 0) {
                break
            }
            if (read == 0) {
                continue
            }
            totalRead = accumulateReadBytes(totalRead, read.toLong(), maxBodyBytes.toLong())
            digest.update(buffer, 0, read)
            requestBodySink.write(buffer, 0, read)
        }

        val hexDigest = digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        return requestBodySink.detachHandle(
            sha256Hex = hexDigest,
            sizeBytes = totalRead,
        )
    } catch (error: Throwable) {
        requestBodySink.close()
        throw error
    }
}

private fun accumulateReadBytes(
    current: Long,
    partBytes: Long,
    maxBytes: Long,
): Long {
    if (partBytes < 0) {
        requestRejected("invalid part size", HttpStatusCode.BadRequest)
    }
    if (current > maxBytes || partBytes > maxBytes - current) {
        requestRejected("request body too large", HttpStatusCode.PayloadTooLarge)
    }
    return current + partBytes
}

private class RequestBodySink(
    private val policy: RequestBodyBufferingPolicy = RequestBodyBufferingPolicy.default(),
) : AutoCloseable {
    private var totalBufferedBytes = 0
    private var storage: RequestBodyStorage =
        InMemoryRequestBodyStorage(minOf(policy.maxInMemoryBytes, STREAM_BUFFER_BYTES))

    fun write(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ) {
        val nextTotalBufferedBytes = totalBufferedBytes + length
        if (storage is InMemoryRequestBodyStorage && nextTotalBufferedBytes > policy.maxInMemoryBytes) {
            storage = spillToDisk(storage as InMemoryRequestBodyStorage)
        }
        storage.write(bytes, offset, length)
        totalBufferedBytes = nextTotalBufferedBytes
    }

    fun detachHandle(
        sha256Hex: String,
        sizeBytes: Long,
    ): RequestBodyHandle =
        storage
            .also {
                storage = DetachedRequestBodyStorage
            }.toHandle(
                sha256Hex = sha256Hex,
                sizeBytes = sizeBytes,
            )

    override fun close() {
        storage.close()
    }

    private fun spillToDisk(memoryStorage: InMemoryRequestBodyStorage): RequestBodyStorage {
        val (spillPath, spillStorage) = createSpillStorage()
        return try {
            val inMemoryBytes = memoryStorage.toByteArray()
            if (inMemoryBytes.isNotEmpty()) {
                spillStorage.write(inMemoryBytes, 0, inMemoryBytes.size)
            }
            memoryStorage.close()
            spillStorage
        } catch (error: Throwable) {
            runCatching { spillStorage.close() }
            throw error
        }
    }

    private fun createSpillStorage(): Pair<Path, RequestBodyStorage> =
        createSpillPath(policy.spillDirectory)
            .let { spillPath -> spillPath to createSpillStorage(spillPath) }

    private fun createSpillPath(directory: Path): Path =
        runCatching { prepareSpillPath(directory) }.getOrElse { error ->
            if (!policy.allowJvmTempFallback) {
                throw error
            }
            val fallbackDirectory =
                Path.of(System.getProperty("java.io.tmpdir"), "request-bodies")
            IrisLogger.warn(
                "[RequestBodyReader] Failed to prepare spill directory ${policy.spillDirectory}: ${error.message}. " +
                    "Falling back to $fallbackDirectory",
            )
            prepareSpillPath(fallbackDirectory)
        }

    private fun prepareSpillPath(directory: Path): Path {
        Files.createDirectories(directory)
        return Files.createTempFile(
            directory,
            REQUEST_BODY_TEMP_FILE_PREFIX,
            REQUEST_BODY_TEMP_FILE_SUFFIX,
        )
    }

    private fun createSpillStorage(spillPath: Path): RequestBodyStorage =
        try {
            policy.spillStorageFactory(spillPath)
        } catch (error: Throwable) {
            Files.deleteIfExists(spillPath)
            throw error
        }
}

internal data class RequestBodyBufferingPolicy(
    val maxInMemoryBytes: Int = MAX_IN_MEMORY_BODY_BYTES,
    val spillDirectory: Path,
    private val implicitSpillDirectory: Path? = null,
    val spillStorageFactory: (Path) -> RequestBodyStorage = ::SpillFileRequestBodyStorage,
) {
    init {
        require(maxInMemoryBytes > 0) { "maxInMemoryBytes must be positive" }
    }

    val allowJvmTempFallback: Boolean
        get() = implicitSpillDirectory != null && spillDirectory == implicitSpillDirectory

    companion object {
        private const val MAX_IN_MEMORY_ENV = "IRIS_REQUEST_BODY_MAX_IN_MEMORY_BYTES"
        private const val SPILL_DIR_ENV = "IRIS_REQUEST_BODY_SPILL_DIR"

        fun default(): RequestBodyBufferingPolicy {
            val implicitSpillDirectory =
                java.nio.file.Paths
                    .get(ConfigPathPolicy.resolveRequestBodySpillDirectory())
            return RequestBodyBufferingPolicy(
                spillDirectory =
                    implicitSpillDirectory,
                implicitSpillDirectory = implicitSpillDirectory,
            )
        }

        fun fromEnv(
            env: Map<String, String> = System.getenv(),
        ): RequestBodyBufferingPolicy {
            val configuredMaxInMemoryBytes =
                env[MAX_IN_MEMORY_ENV]
                    ?.trim()
                    ?.toIntOrNull()
                    ?.takeIf { it > 0 }
                    ?: MAX_IN_MEMORY_BODY_BYTES
            val explicitSpillDirectory =
                env[SPILL_DIR_ENV]
                    ?.trim()
                    .orEmpty()
            val configuredSpillDirectory =
                explicitSpillDirectory
                    .ifBlank { ConfigPathPolicy.resolveRequestBodySpillDirectory(env) }
            val spillDirectory =
                java.nio.file.Paths
                    .get(configuredSpillDirectory)
            return RequestBodyBufferingPolicy(
                maxInMemoryBytes = configuredMaxInMemoryBytes,
                spillDirectory = spillDirectory,
                implicitSpillDirectory = spillDirectory.takeIf { explicitSpillDirectory.isBlank() },
            )
        }
    }
}

internal interface RequestBodyStorage : AutoCloseable {
    fun write(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    )

    fun toHandle(
        sha256Hex: String,
        sizeBytes: Long,
    ): RequestBodyHandle
}

private class InMemoryRequestBodyStorage(
    initialCapacity: Int,
) : RequestBodyStorage {
    private val buffer = ByteArrayOutputStream(initialCapacity)

    override fun write(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ) {
        buffer.write(bytes, offset, length)
    }

    fun toByteArray(): ByteArray = buffer.toByteArray()

    override fun toHandle(
        sha256Hex: String,
        sizeBytes: Long,
    ): RequestBodyHandle = InMemoryRequestBodyHandle(buffer.toByteArray(), sha256Hex)

    override fun close() = Unit
}

private class SpillFileRequestBodyStorage(
    private val path: Path,
) : RequestBodyStorage {
    private val output = Files.newOutputStream(path)
    private var writerClosed = false

    override fun write(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ) {
        output.write(bytes, offset, length)
    }

    override fun toHandle(
        sha256Hex: String,
        sizeBytes: Long,
    ): RequestBodyHandle {
        closeWriter()
        return SpillFileRequestBodyHandle(
            path = path,
            sizeBytes = sizeBytes,
            sha256Hex = sha256Hex,
        )
    }

    override fun close() {
        closeWriter()
        Files.deleteIfExists(path)
    }

    private fun closeWriter() {
        if (!writerClosed) {
            output.close()
            writerClosed = true
        }
    }
}

private object DetachedRequestBodyStorage : RequestBodyStorage {
    override fun write(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ) = error("detached storage cannot be written")

    override fun toHandle(
        sha256Hex: String,
        sizeBytes: Long,
    ): RequestBodyHandle = error("detached storage cannot create a handle")

    override fun close() = Unit
}
