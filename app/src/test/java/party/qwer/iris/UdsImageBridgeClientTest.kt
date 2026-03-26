package party.qwer.iris

import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UdsImageBridgeClientTest {
    @Test
    fun `falls back to connect without timeout when timed connect is unsupported`() {
        val responseBuffer = ByteArrayOutputStream()
        ImageBridgeProtocol.writeFrame(
            responseBuffer,
            JSONObject().apply {
                put("status", ImageBridgeProtocol.STATUS_SENT)
            },
        )
        val fakeSocket =
            FakeBridgeSocket(
                connectWithTimeoutError = UnsupportedOperationException("timeout connect unsupported"),
                input = ByteArrayInputStream(responseBuffer.toByteArray()),
            )

        val client =
            UdsImageBridgeClient(
                socketName = "iris-image-bridge",
                socketFactory = { fakeSocket },
            )

        val result =
            client.sendImage(
                roomId = 1L,
                imagePaths = listOf("/tmp/test.png"),
                threadId = 2L,
                threadScope = 2,
            )

        assertTrue(result.success)
        assertEquals(1, fakeSocket.connectWithTimeoutCalls)
        assertEquals(1, fakeSocket.connectCalls)
        assertEquals(30_000, fakeSocket.readTimeoutMs)
        assertTrue(fakeSocket.outputShutdown)
    }

    @Test
    fun `returns failure when both connect variants fail`() {
        val fakeSocket =
            FakeBridgeSocket(
                connectWithTimeoutError = UnsupportedOperationException("timeout connect unsupported"),
                connectError = IOException("connect failed"),
            )

        val client = UdsImageBridgeClient(socketFactory = { fakeSocket })
        val result =
            client.sendImage(
                roomId = 1L,
                imagePaths = listOf("/tmp/test.png"),
                threadId = null,
                threadScope = null,
            )

        assertFalse(result.success)
        assertTrue(result.error?.contains("connect failed") == true)
    }
}

private class FakeBridgeSocket(
    private val connectWithTimeoutError: Throwable? = null,
    private val connectError: Throwable? = null,
    input: ByteArrayInputStream = ByteArrayInputStream(ByteArray(0)),
) : BridgeSocket {
    override val inputStream = input
    override val outputStream = ByteArrayOutputStream()

    var connectWithTimeoutCalls = 0
        private set
    var connectCalls = 0
        private set
    var outputShutdown = false
        private set
    var readTimeoutMs: Int = 0
        private set

    override fun connect(
        socketName: String,
        timeoutMs: Int,
    ) {
        connectWithTimeoutCalls += 1
        when (val error = connectWithTimeoutError) {
            null -> Unit
            is IOException -> throw error
            is RuntimeException -> throw error
            else -> throw RuntimeException(error)
        }
    }

    override fun connect(socketName: String) {
        connectCalls += 1
        when (val error = connectError) {
            null -> Unit
            is IOException -> throw error
            is RuntimeException -> throw error
            else -> throw RuntimeException(error)
        }
    }

    override fun setReadTimeout(timeoutMs: Int) {
        readTimeoutMs = timeoutMs
    }

    override fun shutdownOutput() {
        outputShutdown = true
    }

    override fun close() = Unit
}
