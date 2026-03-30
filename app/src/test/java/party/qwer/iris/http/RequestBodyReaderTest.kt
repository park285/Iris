package party.qwer.iris.http

import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import party.qwer.iris.ApiRequestException
import party.qwer.iris.model.ConfigRequest
import party.qwer.iris.sha256Hex
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

class RequestBodyReaderTest {
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
    fun `large body spills to temp storage during read and removes it after success`() =
        runBlocking {
            supervisorScope {
                withIsolatedJvmTempDir { tempDir ->
                    val firstChunk = "a".repeat(96 * 1024)
                    val secondChunk = "b".repeat(32 * 1024)
                    val bodyChannel = ByteChannel(autoFlush = true)
                    val firstChunkWritten = CompletableDeferred<Unit>()
                    val continueWriting = CompletableDeferred<Unit>()

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
                            )
                        }

                    firstChunkWritten.await()
                    waitForAnyFile(tempDir)

                    continueWriting.complete(Unit)
                    val result = reader.await()
                    writer.join()

                    result.use {
                        assertEquals(firstChunk + secondChunk, it.readUtf8Body())
                        assertEquals(
                            sha256Hex((firstChunk + secondChunk).toByteArray()),
                            it.sha256Hex,
                        )
                    }
                    assertTrue(tempDir.isEmptyDirectory(), "expected spill file to be removed after success")
                }
            }
        }

    @Test
    fun `spilled body decodes json from stream without requiring utf8 body read`() =
        runBlocking {
            val payload = """{"endpoint":"https://example.com/webhook"}"""

            readBodyWithStreamingDigest(
                bodyChannel = ByteReadChannel(payload),
                declaredContentLength = payload.length.toLong(),
                maxBodyBytes = 256 * 1024,
                bufferingPolicy =
                    RequestBodyBufferingPolicy(
                        maxInMemoryBytes = 8,
                        spillStorageFactory = ::NoStringReadSpillStorage,
                    ),
            ).use { result ->
                val decoded = result.decodeJson(kotlinx.serialization.json.Json { ignoreUnknownKeys = true }, ConfigRequest.serializer())
                assertEquals("https://example.com/webhook", decoded.endpoint)
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
                            )
                        }

                    firstChunkWritten.await()
                    waitForAnyFile(tempDir)

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

    private suspend fun waitForAnyFile(tempDir: Path): Path {
        repeat(50) {
            tempDir.firstRegularFileOrNull()?.let { return it }
            delay(20)
        }
        fail("expected a spill file to appear in $tempDir")
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

    private fun Path.firstRegularFileOrNull(): Path? =
        Files.list(this).use { paths ->
            paths.filter(Files::isRegularFile).findFirst().orElse(null)
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

    override fun readUtf8Body(): String = error("unused")

    override fun openInputStream() = error("unused")

    override fun close() {
        Files.deleteIfExists(path)
    }
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

    override fun readUtf8Body(): String = error("string rematerialization should not be used in this test")

    override fun openInputStream() =
        Files.newInputStream(
            path.also {
                closeWriter()
            },
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
