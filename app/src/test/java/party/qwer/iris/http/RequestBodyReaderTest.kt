package party.qwer.iris.http

import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import party.qwer.iris.ApiRequestException
import party.qwer.iris.config.ConfigPathPolicy
import party.qwer.iris.model.ConfigRequest
import party.qwer.iris.sha256Hex
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RequestBodyReaderTest {
    @Test
    fun `buffering policy reads runtime overrides from env`() {
        val policy =
            RequestBodyBufferingPolicy.fromEnv(
                env =
                    mapOf(
                        "IRIS_REQUEST_BODY_MAX_IN_MEMORY_BYTES" to "8192",
                        "IRIS_REQUEST_BODY_SPILL_DIR" to "/tmp/iris-request-bodies",
                    ),
            )

        assertEquals(8192, policy.maxInMemoryBytes)
        assertEquals(Path.of("/tmp/iris-request-bodies"), policy.spillDirectory)
    }

    @Test
    fun `buffering policy falls back to defaults when env is absent or invalid`() {
        val policy =
            RequestBodyBufferingPolicy.fromEnv(
                env =
                    mapOf(
                        "IRIS_REQUEST_BODY_MAX_IN_MEMORY_BYTES" to "0",
                        "IRIS_DATA_DIR" to "/persistent/iris",
                    ),
            )

        assertEquals(64 * 1024, policy.maxInMemoryBytes)
        assertEquals(Path.of("/persistent/iris/spool/request-bodies"), policy.spillDirectory)
    }

    @Test
    fun `buffering policy uses Iris spill path when env is empty`() {
        val policy = RequestBodyBufferingPolicy.fromEnv(env = emptyMap())

        assertEquals(Path.of(ConfigPathPolicy.resolveRequestBodySpillDirectory(emptyMap())), policy.spillDirectory)
    }

    @Test
    fun `rejects when Content-Length exceeds limit`() =
        runBlocking {
            val error =
                assertFailsWith<ApiRequestException> {
                    readBodyWithStreamingDigest(
                        bodyChannel = ByteReadChannel("small"),
                        declaredContentLength = 1_000_000,
                        maxBodyBytes = 1024,
                    )
                }
            assertEquals(HttpStatusCode.PayloadTooLarge, error.status)
        }

    @Test
    fun `rejects when actual body exceeds limit despite no Content-Length`() =
        runBlocking {
            val largeBody = "x".repeat(100)
            val error =
                assertFailsWith<ApiRequestException> {
                    readBodyWithStreamingDigest(
                        bodyChannel = ByteReadChannel(largeBody),
                        declaredContentLength = null,
                        maxBodyBytes = 50,
                    )
                }
            assertEquals(HttpStatusCode.PayloadTooLarge, error.status)
        }

    @Test
    fun `rejects negative Content-Length`() =
        runBlocking {
            val error =
                assertFailsWith<ApiRequestException> {
                    readBodyWithStreamingDigest(
                        bodyChannel = ByteReadChannel("small"),
                        declaredContentLength = -1,
                        maxBodyBytes = 1024,
                    )
                }
            assertEquals(HttpStatusCode.BadRequest, error.status)
        }

    @Test
    fun `returns body and streaming sha256 digest`() =
        runBlocking {
            val payload = """{"action":"test","data":"hello"}"""
            readBodyWithStreamingDigest(
                bodyChannel = ByteReadChannel(payload),
                declaredContentLength = payload.length.toLong(),
                maxBodyBytes = 1024,
            ).use { result ->
                assertEquals(payload, result.readUtf8Body())
                assertEquals(sha256Hex(payload.toByteArray()), result.sha256Hex)
                assertEquals(payload.toByteArray().size.toLong(), result.sizeBytes)
            }
        }

    @Test
    fun `streaming digest matches single-pass digest for large payloads`() =
        runBlocking {
            val payload = "A".repeat(32_768)
            val result =
                readBodyWithStreamingDigest(
                    bodyChannel = ByteReadChannel(payload),
                    declaredContentLength = payload.length.toLong(),
                    maxBodyBytes = 64 * 1024,
                )
            assertEquals(sha256Hex(payload.toByteArray()), result.sha256Hex)
        }

    @Test
    fun `empty body returns empty string and correct digest`() =
        runBlocking {
            readBodyWithStreamingDigest(
                bodyChannel = ByteReadChannel(""),
                declaredContentLength = 0,
                maxBodyBytes = 1024,
            ).use { result ->
                assertEquals("", result.readUtf8Body())
                assertEquals(sha256Hex(ByteArray(0)), result.sha256Hex)
            }
        }

    @Test
    fun `accepts payload exactly at limit`() =
        runBlocking {
            val payload = "x".repeat(64)
            readBodyWithStreamingDigest(
                bodyChannel = ByteReadChannel(payload),
                declaredContentLength = 64,
                maxBodyBytes = 64,
            ).use { result ->
                assertEquals(payload, result.readUtf8Body())
            }
        }

    @Test
    fun `accumulateReplyBodyBytes rejects overflowing addition`() {
        val error =
            assertFailsWith<ApiRequestException> {
                accumulateReplyBodyBytes(
                    current = Long.MAX_VALUE - 4,
                    partBytes = 10,
                    maxBytes = Long.MAX_VALUE,
                )
            }
        assertEquals(HttpStatusCode.PayloadTooLarge, error.status)
    }

    @Test
    fun `large body spills to temp storage during read and removes it after success`() =
        runBlocking {
            supervisorScope {
                withIsolatedJvmTempDir { tempDir ->
                    val firstChunk = "a".repeat(96 * 1024)
                    val secondChunk = "b".repeat(32 * 1024)
                    val bodyChannel = ByteChannel(autoFlush = true)
                    val firstChunkWritten = CompletableDeferred<Unit>()
                    val continueWriting = CompletableDeferred<Unit>()
                    val spillPathReady = CompletableDeferred<Path>()

                    val writer =
                        launch {
                            bodyChannel.writeFully(firstChunk.toByteArray())
                            firstChunkWritten.complete(Unit)
                            continueWriting.await()
                            bodyChannel.writeFully(secondChunk.toByteArray())
                            bodyChannel.close()
                        }

                    val reader =
                        async {
                            readBodyWithStreamingDigest(
                                bodyChannel = bodyChannel,
                                declaredContentLength = (firstChunk.length + secondChunk.length).toLong(),
                                maxBodyBytes = 256 * 1024,
                                bufferingPolicy =
                                    RequestBodyBufferingPolicy(
                                        maxInMemoryBytes = 64 * 1024,
                                        spillDirectory = tempDir,
                                        spillStorageFactory = { path -> ObservingSpillStorage(path, spillPathReady) },
                                    ),
                            )
                        }

                    firstChunkWritten.await()
                    val spillPath = spillPathReady.await()
                    assertTrue(Files.exists(spillPath), "expected spill file to exist before read completes")

                    continueWriting.complete(Unit)
                    val result = reader.await()
                    writer.join()

                    result.use {
                        assertTrue(it is SpillFileRequestBodyHandle)
                        assertEquals(firstChunk + secondChunk, it.readUtf8Body())
                        assertEquals(
                            sha256Hex((firstChunk + secondChunk).toByteArray()),
                            it.sha256Hex,
                        )
                        assertEquals((firstChunk.length + secondChunk.length).toLong(), it.sizeBytes)
                    }
                    assertTrue(tempDir.isEmptyDirectory(), "expected spill file to be removed after success")
                }
            }
        }

    @Test
    fun `spilled body decodes json from stream without requiring utf8 body read`() =
        runBlocking {
            val payload = """{"endpoint":"https://example.com/webhook"}"""
            val spillDir = Files.createTempDirectory("request-body-json-spill-")

            try {
                readBodyWithStreamingDigest(
                    bodyChannel = ByteReadChannel(payload),
                    declaredContentLength = payload.length.toLong(),
                    maxBodyBytes = 256 * 1024,
                    bufferingPolicy =
                        RequestBodyBufferingPolicy(
                            maxInMemoryBytes = 8,
                            spillDirectory = spillDir,
                            spillStorageFactory = ::NoStringReadSpillStorage,
                        ),
                ).use { result ->
                    val decoded = result.decodeJson(kotlinx.serialization.json.Json { ignoreUnknownKeys = true }, ConfigRequest.serializer())
                    assertEquals("https://example.com/webhook", decoded.endpoint)
                }
            } finally {
                spillDir.deleteRecursively()
            }
        }

    @Test
    fun `spilled temp file is removed when body later exceeds limit`() =
        runBlocking {
            supervisorScope {
                withIsolatedJvmTempDir { tempDir ->
                    val firstChunk = "x".repeat(96 * 1024)
                    val secondChunk = "y".repeat(32 * 1024)
                    val bodyChannel = ByteChannel(autoFlush = true)
                    val firstChunkWritten = CompletableDeferred<Unit>()
                    val continueWriting = CompletableDeferred<Unit>()
                    val spillPathReady = CompletableDeferred<Path>()

                    val writer =
                        launch {
                            bodyChannel.writeFully(firstChunk.toByteArray())
                            firstChunkWritten.complete(Unit)
                            continueWriting.await()
                            bodyChannel.writeFully(secondChunk.toByteArray())
                            bodyChannel.close()
                        }

                    val reader =
                        async {
                            readBodyWithStreamingDigest(
                                bodyChannel = bodyChannel,
                                declaredContentLength = null,
                                maxBodyBytes = 100 * 1024,
                                bufferingPolicy =
                                    RequestBodyBufferingPolicy(
                                        maxInMemoryBytes = 64 * 1024,
                                        spillDirectory = tempDir,
                                        spillStorageFactory = { path -> ObservingSpillStorage(path, spillPathReady) },
                                    ),
                            )
                        }

                    firstChunkWritten.await()
                    val spillPath = spillPathReady.await()
                    assertTrue(Files.exists(spillPath), "expected spill file to exist before overflow failure")

                    continueWriting.complete(Unit)
                    val error = assertFailsWith<ApiRequestException> { reader.await() }
                    writer.join()

                    assertEquals(HttpStatusCode.PayloadTooLarge, error.status)
                    assertTrue(tempDir.isEmptyDirectory(), "expected spill file to be removed after failure")
                }
            }
        }

    @Test
    fun `spill failure removes temp file before propagating error`() =
        runBlocking {
            supervisorScope {
                withIsolatedJvmTempDir { tempDir ->
                    val error =
                        assertFailsWith<IllegalStateException> {
                            readBodyWithStreamingDigest(
                                bodyChannel = ByteReadChannel("z".repeat(96 * 1024)),
                                declaredContentLength = null,
                                maxBodyBytes = 256 * 1024,
                                bufferingPolicy =
                                    RequestBodyBufferingPolicy(
                                        maxInMemoryBytes = 64,
                                        spillDirectory = tempDir,
                                        spillStorageFactory = ::FailingSpillStorage,
                                    ),
                            )
                        }

                    assertEquals("spill write failed", error.message)
                    assertTrue(tempDir.isEmptyDirectory(), "expected spill file to be removed after write failure")
                }
            }
        }

    @Test
    fun `implicit default path does not fall back when spill storage factory fails`() =
        runBlocking {
            withIsolatedJvmTempDir { tempDir ->
                val dataDir = Files.createTempDirectory("request-body-spill-data-")
                try {
                    val error =
                        assertFailsWith<IllegalStateException> {
                            readBodyWithStreamingDigest(
                                bodyChannel = ByteReadChannel("z".repeat(96 * 1024)),
                                declaredContentLength = null,
                                maxBodyBytes = 256 * 1024,
                                bufferingPolicy =
                                    RequestBodyBufferingPolicy
                                        .fromEnv(
                                            env =
                                                mapOf(
                                                    "IRIS_DATA_DIR" to dataDir.toString(),
                                                    "IRIS_REQUEST_BODY_MAX_IN_MEMORY_BYTES" to "64",
                                                ),
                                        ).copy(
                                            spillStorageFactory = ::failingOpenSpillStorage,
                                        ),
                            )
                        }

                    assertEquals("spill open failed", error.message)
                    assertTrue(
                        Files.notExists(tempDir.resolve("request-bodies")),
                        "expected spill storage factory failure to avoid jvm temp fallback",
                    )
                } finally {
                    dataDir.deleteRecursively()
                }
            }
        }

    @Test
    fun `spill path is created on demand when configured directory does not exist`() =
        runBlocking {
            val baseDir = Files.createTempDirectory("request-body-spill-parent-")
            val missingSpillDir = baseDir.resolve("nested").resolve("spill")
            try {
                readBodyWithStreamingDigest(
                    bodyChannel = ByteReadChannel("z".repeat(96 * 1024)),
                    declaredContentLength = null,
                    maxBodyBytes = 256 * 1024,
                    bufferingPolicy =
                        RequestBodyBufferingPolicy(
                            maxInMemoryBytes = 64,
                            spillDirectory = missingSpillDir,
                        ),
                ).use { result ->
                    assertTrue(result is SpillFileRequestBodyHandle)
                }

                assertTrue(Files.isDirectory(missingSpillDir), "expected spill directory to be created automatically")
            } finally {
                baseDir.deleteRecursively()
            }
        }

    @Test
    fun `implicit default spill path falls back to jvm temp dir on host`() =
        runBlocking {
            withIsolatedJvmTempDir { tempDir ->
                val baseDir = Files.createTempDirectory("request-body-spill-blocked-")
                val blockingFile = baseDir.resolve("not-a-directory")
                Files.write(blockingFile, "blocked".toByteArray())
                val impossibleDataDir = blockingFile

                try {
                    readBodyWithStreamingDigest(
                        bodyChannel = ByteReadChannel("z".repeat(96 * 1024)),
                        declaredContentLength = null,
                        maxBodyBytes = 256 * 1024,
                        bufferingPolicy =
                            RequestBodyBufferingPolicy.fromEnv(
                                env =
                                    mapOf(
                                        "IRIS_DATA_DIR" to impossibleDataDir.toString(),
                                        "IRIS_REQUEST_BODY_MAX_IN_MEMORY_BYTES" to "64",
                                    ),
                            ),
                    ).use { result ->
                        assertTrue(result is SpillFileRequestBodyHandle)
                        assertEquals("z".repeat(96 * 1024), result.readUtf8Body())
                    }

                    assertTrue(
                        Files.exists(tempDir.resolve("request-bodies")),
                        "expected fallback spill directory under java.io.tmpdir to be used",
                    )
                } finally {
                    baseDir.deleteRecursively()
                }
            }
        }

    @Test
    fun `explicit spill dir env override does not fall back to jvm temp dir`() =
        runBlocking {
            withIsolatedJvmTempDir { tempDir ->
                val baseDir = Files.createTempDirectory("request-body-spill-blocked-")
                val blockingFile = baseDir.resolve("not-a-directory")
                Files.write(blockingFile, "blocked".toByteArray())
                val impossibleSpillDir = blockingFile.resolve("nested")

                try {
                    val error =
                        assertFailsWith<java.nio.file.FileSystemException> {
                            readBodyWithStreamingDigest(
                                bodyChannel = ByteReadChannel("z".repeat(96 * 1024)),
                                declaredContentLength = null,
                                maxBodyBytes = 256 * 1024,
                                bufferingPolicy =
                                    RequestBodyBufferingPolicy.fromEnv(
                                        env =
                                            mapOf(
                                                "IRIS_REQUEST_BODY_SPILL_DIR" to impossibleSpillDir.toString(),
                                                "IRIS_REQUEST_BODY_MAX_IN_MEMORY_BYTES" to "64",
                                            ),
                                    ),
                            )
                        }

                    assertTrue(
                        error.message.orEmpty().contains(impossibleSpillDir.parent.toString()),
                        "expected explicit spill dir failure to mention the configured path",
                    )
                    assertTrue(
                        Files.notExists(tempDir.resolve("request-bodies")),
                        "expected explicit spill dir failure to avoid jvm temp fallback",
                    )
                } finally {
                    baseDir.deleteRecursively()
                }
            }
        }

    @Test
    fun `customized default policy spill dir does not fall back to jvm temp dir`() =
        runBlocking {
            withIsolatedJvmTempDir { tempDir ->
                val baseDir = Files.createTempDirectory("request-body-spill-blocked-")
                val blockingFile = baseDir.resolve("not-a-directory")
                Files.write(blockingFile, "blocked".toByteArray())
                val impossibleSpillDir = blockingFile.resolve("nested")

                try {
                    val error =
                        assertFailsWith<java.nio.file.FileSystemException> {
                            readBodyWithStreamingDigest(
                                bodyChannel = ByteReadChannel("z".repeat(96 * 1024)),
                                declaredContentLength = null,
                                maxBodyBytes = 256 * 1024,
                                bufferingPolicy =
                                    RequestBodyBufferingPolicy
                                        .default()
                                        .copy(
                                            maxInMemoryBytes = 64,
                                            spillDirectory = impossibleSpillDir,
                                        ),
                            )
                        }

                    assertTrue(
                        error.message.orEmpty().contains(impossibleSpillDir.parent.toString()),
                        "expected customized default policy failure to mention the configured path",
                    )
                    assertTrue(
                        Files.notExists(tempDir.resolve("request-bodies")),
                        "expected customized default policy failure to avoid jvm temp fallback",
                    )
                } finally {
                    baseDir.deleteRecursively()
                }
            }
        }

    private suspend fun withIsolatedJvmTempDir(block: suspend (Path) -> Unit) {
        val previous = System.getProperty("java.io.tmpdir")
        val tempDir = Files.createTempDirectory("request-body-reader-test-")
        System.setProperty("java.io.tmpdir", tempDir.toString())
        try {
            block(tempDir)
        } finally {
            System.setProperty("java.io.tmpdir", previous)
            tempDir.deleteRecursively()
        }
    }

    private fun Path.isEmptyDirectory(): Boolean =
        Files.list(this).use { paths ->
            paths.findAny().isEmpty
        }

    private fun Path.deleteRecursively() {
        Files.walk(this).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }
}

private class FailingSpillStorage(
    private val path: Path,
) : RequestBodyStorage {
    override fun write(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): Unit = throw IllegalStateException("spill write failed")

    override fun toHandle(
        sha256Hex: String,
        sizeBytes: Long,
    ): RequestBodyHandle = error("unused")

    override fun close() {
        Files.deleteIfExists(path)
    }
}

private fun failingOpenSpillStorage(path: Path): RequestBodyStorage {
    Files.deleteIfExists(path)
    throw IllegalStateException("spill open failed")
}

private class NoStringReadSpillStorage(
    private val path: Path,
) : RequestBodyStorage {
    private val delegate = Files.newOutputStream(path)
    private var writerClosed = false

    override fun write(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ) {
        delegate.write(bytes, offset, length)
    }

    override fun toHandle(
        sha256Hex: String,
        sizeBytes: Long,
    ): RequestBodyHandle =
        SpillFileRequestBodyHandle(
            path =
                path.also {
                    closeWriter()
                },
            sizeBytes = sizeBytes,
            sha256Hex = sha256Hex,
        )

    override fun close() {
        closeWriter()
        Files.deleteIfExists(path)
    }

    private fun closeWriter() {
        if (!writerClosed) {
            delegate.close()
            writerClosed = true
        }
    }
}

private class ObservingSpillStorage(
    private val path: Path,
    private val spillPathReady: CompletableDeferred<Path>,
) : RequestBodyStorage {
    private val delegate = Files.newOutputStream(path)
    private var writerClosed = false

    override fun write(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ) {
        if (!spillPathReady.isCompleted) {
            spillPathReady.complete(path)
        }
        delegate.write(bytes, offset, length)
    }

    override fun toHandle(
        sha256Hex: String,
        sizeBytes: Long,
    ): RequestBodyHandle =
        SpillFileRequestBodyHandle(
            path =
                path.also {
                    closeWriter()
                },
            sizeBytes = sizeBytes,
            sha256Hex = sha256Hex,
        )

    override fun close() {
        closeWriter()
        Files.deleteIfExists(path)
    }

    private fun closeWriter() {
        if (!writerClosed) {
            delegate.close()
            writerClosed = true
        }
    }
}
