package party.qwer.iris.http

import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import party.qwer.iris.ApiRequestException
import party.qwer.iris.sha256Hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
            val result =
                readBodyWithStreamingDigest(
                    bodyChannel = ByteReadChannel(payload),
                    declaredContentLength = payload.length.toLong(),
                    maxBodyBytes = 1024,
                )
            assertEquals(payload, result.body)
            assertEquals(sha256Hex(payload.toByteArray()), result.sha256Hex)
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
            val result =
                readBodyWithStreamingDigest(
                    bodyChannel = ByteReadChannel(""),
                    declaredContentLength = 0,
                    maxBodyBytes = 1024,
                )
            assertEquals("", result.body)
            assertEquals(sha256Hex(ByteArray(0)), result.sha256Hex)
        }

    @Test
    fun `accepts payload exactly at limit`() =
        runBlocking {
            val payload = "x".repeat(64)
            val result =
                readBodyWithStreamingDigest(
                    bodyChannel = ByteReadChannel(payload),
                    declaredContentLength = 64,
                    maxBodyBytes = 64,
                )
            assertEquals(payload, result.body)
        }
}
