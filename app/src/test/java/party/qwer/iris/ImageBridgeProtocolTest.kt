package party.qwer.iris

import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.EOFException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ImageBridgeProtocolTest {
    @Test
    fun `writeFrame then readFrame roundtrips`() {
        val original =
            JSONObject().apply {
                put("action", "send_image")
                put("roomId", 12345L)
            }
        val buffer = ByteArrayOutputStream()
        ImageBridgeProtocol.writeFrame(buffer, original)

        val restored = ImageBridgeProtocol.readFrame(ByteArrayInputStream(buffer.toByteArray()))
        assertEquals("send_image", restored.getString("action"))
        assertEquals(12345L, restored.getLong("roomId"))
    }

    @Test
    fun `readFrame rejects frame exceeding max size`() {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).writeInt(ImageBridgeProtocol.MAX_FRAME_SIZE + 1)
        assertFailsWith<IllegalArgumentException> {
            ImageBridgeProtocol.readFrame(ByteArrayInputStream(buffer.toByteArray()))
        }
    }

    @Test
    fun `readFrame rejects zero-length frame`() {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).writeInt(0)
        assertFailsWith<IllegalArgumentException> {
            ImageBridgeProtocol.readFrame(ByteArrayInputStream(buffer.toByteArray()))
        }
    }

    @Test
    fun `readFrame rejects negative frame size`() {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).writeInt(-1)
        assertFailsWith<IllegalArgumentException> {
            ImageBridgeProtocol.readFrame(ByteArrayInputStream(buffer.toByteArray()))
        }
    }

    @Test
    fun `readFrame throws on truncated payload`() {
        val buffer = ByteArrayOutputStream()
        val dos = DataOutputStream(buffer)
        dos.writeInt(100)
        dos.write(ByteArray(10))
        assertFailsWith<EOFException> {
            ImageBridgeProtocol.readFrame(ByteArrayInputStream(buffer.toByteArray()))
        }
    }

    @Test
    fun `buildSendImageRequest includes all fields`() {
        val request =
            ImageBridgeProtocol.buildSendImageRequest(
                roomId = 999L,
                imagePaths = listOf("/a.png", "/b.png"),
                threadId = 42L,
                threadScope = 2,
            )
        assertEquals("send_image", request.getString("action"))
        assertEquals(999L, request.getLong("roomId"))
        assertEquals(2, request.getJSONArray("imagePaths").length())
        assertEquals("/a.png", request.getJSONArray("imagePaths").getString(0))
        assertEquals(42L, request.getLong("threadId"))
        assertEquals(2, request.getInt("threadScope"))
    }

    @Test
    fun `buildSendImageRequest omits null threadId and threadScope`() {
        val request =
            ImageBridgeProtocol.buildSendImageRequest(
                roomId = 1L,
                imagePaths = listOf("/x.png"),
                threadId = null,
                threadScope = null,
            )
        assertTrue(!request.has("threadId"))
        assertTrue(!request.has("threadScope"))
    }

    @Test
    fun `buildSuccessResponse has sent status`() {
        val response = ImageBridgeProtocol.buildSuccessResponse()
        assertEquals("sent", response.getString("status"))
    }

    @Test
    fun `buildFailureResponse carries error message`() {
        val response = ImageBridgeProtocol.buildFailureResponse("room not found")
        assertEquals("failed", response.getString("status"))
        assertEquals("room not found", response.getString("error"))
    }

    @Test
    fun `multiple frames in sequence`() {
        val buffer = ByteArrayOutputStream()
        val msg1 = JSONObject().apply { put("seq", 1) }
        val msg2 = JSONObject().apply { put("seq", 2) }
        ImageBridgeProtocol.writeFrame(buffer, msg1)
        ImageBridgeProtocol.writeFrame(buffer, msg2)

        val input = ByteArrayInputStream(buffer.toByteArray())
        assertEquals(1, ImageBridgeProtocol.readFrame(input).getInt("seq"))
        assertEquals(2, ImageBridgeProtocol.readFrame(input).getInt("seq"))
    }
}
