package party.qwer.iris.http

import party.qwer.iris.ReplyImagePolicy
import party.qwer.iris.SpillFileVerifiedImagePayloadHandle
import java.nio.file.Files
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class Base64ImageIngressServiceTest {
    companion object {
        private val VALID_TEST_PNG_BYTES =
            byteArrayOf(
                0x89.toByte(),
                0x50,
                0x4E,
                0x47,
                0x0D,
                0x0A,
                0x1A,
                0x0A,
                0x00,
                0x00,
                0x00,
                0x0D,
                0x49,
                0x48,
                0x44,
                0x52,
            )
    }

    @Test
    fun `decodeToVerifiedHandles stages large base64 payload to spill file`() {
        val spillDir = Files.createTempDirectory("iris-base64-ingress")
        val policy =
            ReplyImageIngressPolicy(
                imagePolicy = ReplyImagePolicy(),
                bufferingPolicy = RequestBodyBufferingPolicy(maxInMemoryBytes = 1, spillDirectory = spillDir),
            )
        val service = Base64ImageIngressService()

        val handles =
            service.decodeToVerifiedHandles(
                payloads =
                    listOf(
                        Base64ImagePayload(
                            base64 = Base64.getEncoder().encodeToString(VALID_TEST_PNG_BYTES),
                            contentType = "image/png",
                        ),
                    ),
                replyImageIngressPolicy = policy,
            )

        assertEquals(1, handles.size)
        assertTrue(handles.single() is SpillFileVerifiedImagePayloadHandle)
        assertEquals(1, Files.list(spillDir).use { stream -> stream.count() })
        handles.forEach { handle -> handle.close() }
        assertEquals(0, Files.list(spillDir).use { stream -> stream.count() })
    }

    @Test
    fun `decodeToVerifiedHandles rejects mismatched declared content type`() {
        val spillDir = Files.createTempDirectory("iris-base64-ingress")
        val policy =
            ReplyImageIngressPolicy(
                imagePolicy = ReplyImagePolicy(),
                bufferingPolicy = RequestBodyBufferingPolicy(maxInMemoryBytes = 1, spillDirectory = spillDir),
            )
        val service = Base64ImageIngressService()

        assertFailsWith<IllegalArgumentException> {
            service.decodeToVerifiedHandles(
                payloads =
                    listOf(
                        Base64ImagePayload(
                            base64 = Base64.getEncoder().encodeToString(VALID_TEST_PNG_BYTES),
                            contentType = "image/jpeg",
                        ),
                    ),
                replyImageIngressPolicy = policy,
            )
        }
        assertEquals(0, Files.list(spillDir).use { stream -> stream.count() })
    }

    @Test
    fun `decodeToVerifiedHandles cleans staged handles when later payload is invalid base64`() {
        val spillDir = Files.createTempDirectory("iris-base64-ingress")
        val policy =
            ReplyImageIngressPolicy(
                imagePolicy = ReplyImagePolicy(),
                bufferingPolicy = RequestBodyBufferingPolicy(maxInMemoryBytes = 1, spillDirectory = spillDir),
            )
        val service = Base64ImageIngressService()

        assertFailsWith<IllegalArgumentException> {
            service.decodeToVerifiedHandles(
                payloads =
                    listOf(
                        Base64ImagePayload(
                            base64 = Base64.getEncoder().encodeToString(VALID_TEST_PNG_BYTES),
                            contentType = "image/png",
                        ),
                        Base64ImagePayload(
                            base64 = "%%%not-base64%%%",
                            contentType = "image/png",
                        ),
                    ),
                replyImageIngressPolicy = policy,
            )
        }
        assertEquals(0, Files.list(spillDir).use { stream -> stream.count() })
    }
}
