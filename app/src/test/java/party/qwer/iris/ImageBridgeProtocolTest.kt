package party.qwer.iris

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.EOFException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImageBridgeProtocolTest {
    @Test
    fun `typed request frame roundtrips`() {
        val request =
            ImageBridgeProtocol.buildSendImageRequest(
                roomId = 999L,
                imagePaths = listOf("/a.png", "/b.png"),
                threadId = 42L,
                threadScope = 2,
                requestId = "req-1",
                token = "bridge-token",
            )
        val buffer = ByteArrayOutputStream()

        ImageBridgeProtocol.writeFrame(buffer, request)

        val restored = ImageBridgeProtocol.readRequestFrame(ByteArrayInputStream(buffer.toByteArray()))
        assertEquals(ImageBridgeProtocol.ACTION_SEND_IMAGE, restored.action)
        assertEquals(ImageBridgeProtocol.PROTOCOL_VERSION, restored.protocolVersion)
        assertEquals(999L, restored.roomId)
        assertEquals(listOf("/a.png", "/b.png"), restored.imagePaths)
        assertEquals(42L, restored.threadId)
        assertEquals(2, restored.threadScope)
        assertEquals("req-1", restored.requestId)
        assertEquals("bridge-token", restored.token)
    }

    @Test
    fun `typed response frame roundtrips`() {
        val response =
            ImageBridgeProtocol.ImageBridgeResponse(
                status = ImageBridgeProtocol.STATUS_OK,
                running = true,
                specReady = false,
                checkedAtEpochMs = 1234L,
                restartCount = 2,
            )
        val buffer = ByteArrayOutputStream()

        ImageBridgeProtocol.writeFrame(buffer, response)

        val restored = ImageBridgeProtocol.readResponseFrame(ByteArrayInputStream(buffer.toByteArray()))
        assertEquals(ImageBridgeProtocol.STATUS_OK, restored.status)
        assertTrue(restored.running == true)
        assertFalse(restored.specReady == true)
        assertEquals(1234L, restored.checkedAtEpochMs)
        assertEquals(2, restored.restartCount)
    }

    @Test
    fun `readResponseFrame rejects frame exceeding max size`() {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).writeInt(ImageBridgeProtocol.MAX_FRAME_SIZE + 1)
        assertFailsWith<IllegalArgumentException> {
            ImageBridgeProtocol.readResponseFrame(ByteArrayInputStream(buffer.toByteArray()))
        }
    }

    @Test
    fun `readRequestFrame rejects zero-length frame`() {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).writeInt(0)
        assertFailsWith<IllegalArgumentException> {
            ImageBridgeProtocol.readRequestFrame(ByteArrayInputStream(buffer.toByteArray()))
        }
    }

    @Test
    fun `readResponseFrame rejects negative frame size`() {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).writeInt(-1)
        assertFailsWith<IllegalArgumentException> {
            ImageBridgeProtocol.readResponseFrame(ByteArrayInputStream(buffer.toByteArray()))
        }
    }

    @Test
    fun `readResponseFrame throws on truncated payload`() {
        val buffer = ByteArrayOutputStream()
        val dos = DataOutputStream(buffer)
        dos.writeInt(100)
        dos.write(ByteArray(10))
        assertFailsWith<EOFException> {
            ImageBridgeProtocol.readResponseFrame(ByteArrayInputStream(buffer.toByteArray()))
        }
    }

    @Test
    fun `buildHealthRequest includes protocol metadata`() {
        val request = ImageBridgeProtocol.buildHealthRequest(token = "bridge-token")

        assertEquals(ImageBridgeProtocol.ACTION_HEALTH, request.action)
        assertEquals(ImageBridgeProtocol.PROTOCOL_VERSION, request.protocolVersion)
        assertEquals("bridge-token", request.token)
    }

    @Test
    fun `buildSuccessResponse has sent status`() {
        val response = ImageBridgeProtocol.buildSuccessResponse()
        assertEquals(ImageBridgeProtocol.STATUS_SENT, response.status)
        assertEquals(null, response.error)
    }

    @Test
    fun `buildFailureResponse carries error message`() {
        val response = ImageBridgeProtocol.buildFailureResponse("room not found")
        assertEquals(ImageBridgeProtocol.STATUS_FAILED, response.status)
        assertEquals("room not found", response.error)
    }

    @Test
    fun `writeFrame rejects frame exceeding max size`() {
        val oversized =
            ImageBridgeProtocol.ImageBridgeResponse(
                status = ImageBridgeProtocol.STATUS_FAILED,
                error = "A".repeat(ImageBridgeProtocol.MAX_FRAME_SIZE),
            )

        assertFailsWith<IllegalArgumentException> {
            ImageBridgeProtocol.writeFrame(ByteArrayOutputStream(), oversized)
        }
    }

    @Test
    fun `multiple typed frames in sequence`() {
        val buffer = ByteArrayOutputStream()
        val msg1 =
            ImageBridgeProtocol.buildHealthRequest(token = "one")
        val msg2 =
            ImageBridgeProtocol.buildHealthRequest(token = "two")
        ImageBridgeProtocol.writeFrame(buffer, msg1)
        ImageBridgeProtocol.writeFrame(buffer, msg2)

        val input = ByteArrayInputStream(buffer.toByteArray())
        assertEquals("one", ImageBridgeProtocol.readRequestFrame(input).token)
        assertEquals("two", ImageBridgeProtocol.readRequestFrame(input).token)
    }
}
