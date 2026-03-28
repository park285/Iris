package party.qwer.iris

import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class IrisServerRequestBodyTest {
    @Test
    fun `read request body rejects payloads above limit`() =
        runBlocking {
            val error =
                assertFailsWith<ApiRequestException> {
                    readRequestBodyWithinLimit(
                        bodyChannel = ByteReadChannel("too-large"),
                        declaredContentLength = null,
                        maxBodyBytes = 4,
                    )
                }

            assertEquals(HttpStatusCode.PayloadTooLarge, error.status)
        }

    @Test
    fun `read request body accepts payload within limit`() =
        runBlocking {
            val result =
                readRequestBodyWithinLimit(
                    bodyChannel = ByteReadChannel("""{"ok":true}"""),
                    declaredContentLength = 11,
                    maxBodyBytes = 64,
                )

            assertEquals("""{"ok":true}""", result.body)
            assertEquals(
                sha256Hex("""{"ok":true}""".toByteArray()),
                result.sha256Hex,
            )
        }
}
