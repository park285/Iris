package party.qwer.iris.http

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import party.qwer.iris.requestRejected
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
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
): StreamingBodyResult =
    readBodyWithStreamingDigest(
        bodyChannel = call.receiveChannel(),
        declaredContentLength = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull(),
        maxBodyBytes = maxBodyBytes,
    )

internal suspend fun readBodyWithStreamingDigest(
    bodyChannel: ByteReadChannel,
    declaredContentLength: Long?,
    maxBodyBytes: Int,
    bufferingPolicy: RequestBodyBufferingPolicy = RequestBodyBufferingPolicy(),
): StreamingBodyResult {
    if (declaredContentLength != null && declaredContentLength > maxBodyBytes) {
        requestRejected("request body too large", HttpStatusCode.PayloadTooLarge)
    }

    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(STREAM_BUFFER_BYTES)
    var totalRead = 0

    val requestBodySink = RequestBodySink(policy = bufferingPolicy)
    try {
        while (true) {
            val read = bodyChannel.readAvailable(buffer, 0, buffer.size)
            if (read == -1) {
                break
            }
            totalRead += read
            if (totalRead > maxBodyBytes) {
                requestRejected("request body too large", HttpStatusCode.PayloadTooLarge)
            }
            digest.update(buffer, 0, read)
            requestBodySink.write(buffer, 0, read)
        }

        val hexDigest = digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        return StreamingBodyResult(
            storage = requestBodySink.detachStorage(),
            sha256Hex = hexDigest,
        )
    } catch (error: Throwable) {
        requestBodySink.close()
        throw error
    }
}

private class RequestBodySink(
    private val policy: RequestBodyBufferingPolicy = RequestBodyBufferingPolicy(),
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

    fun readUtf8Body(): String = storage.readUtf8Body()

    fun detachStorage(): RequestBodyStorage =
        storage.also {
            storage = DetachedRequestBodyStorage
        }

    override fun close() {
        storage.close()
    }

    private fun spillToDisk(memoryStorage: InMemoryRequestBodyStorage): RequestBodyStorage {
        val spillPath =
            Files.createTempFile(
                policy.spillDirectory,
                REQUEST_BODY_TEMP_FILE_PREFIX,
                REQUEST_BODY_TEMP_FILE_SUFFIX,
            )
        val spillStorage =
            try {
                policy.spillStorageFactory(spillPath)
            } catch (error: Throwable) {
                Files.deleteIfExists(spillPath)
                throw error
            }
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
}

internal data class RequestBodyBufferingPolicy(
    val maxInMemoryBytes: Int = MAX_IN_MEMORY_BODY_BYTES,
    val spillDirectory: Path = Path.of(System.getProperty("java.io.tmpdir")),
    val spillStorageFactory: (Path) -> RequestBodyStorage = ::SpillFileRequestBodyStorage,
) {
    init {
        require(maxInMemoryBytes > 0) { "maxInMemoryBytes must be positive" }
    }
}

internal interface RequestBodyStorage : AutoCloseable {
    fun write(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    )

    fun readUtf8Body(): String

    fun openInputStream(): InputStream
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

    override fun readUtf8Body(): String = buffer.toString(Charsets.UTF_8.name())

    override fun openInputStream(): InputStream = ByteArrayInputStream(buffer.toByteArray())

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

    override fun readUtf8Body(): String {
        closeWriter()
        return Files.newBufferedReader(path, Charsets.UTF_8).use { reader -> reader.readText() }
    }

    override fun openInputStream(): InputStream {
        closeWriter()
        return Files.newInputStream(path)
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

    override fun readUtf8Body(): String = error("detached storage cannot be read")

    override fun openInputStream(): InputStream = error("detached storage cannot be opened")

    override fun close() = Unit
}
