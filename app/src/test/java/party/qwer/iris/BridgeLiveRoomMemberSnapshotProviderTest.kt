package party.qwer.iris

import party.qwer.iris.storage.ChatId
import party.qwer.iris.storage.UserId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BridgeLiveRoomMemberSnapshotProviderTest {
    @Test
    fun `provider disables permanently after unsupported bridge action`() {
        var socketCreations = 0
        val client =
            UdsImageBridgeClient(
                socketFactory = {
                    socketCreations += 1
                    responseSocket(
                        ImageBridgeProtocol.buildFailureResponse("unknown action: snapshot_chatroom_members"),
                    )
                },
            )
        val provider = BridgeLiveRoomMemberSnapshotProvider(bridgeClient = client, clock = { 1L })

        assertNull(provider.snapshot(ChatId(100L), listOf(LiveRoomMemberHint(userId = UserId(1L), nickname = "Alice"))))
        assertNull(provider.snapshot(ChatId(100L), listOf(LiveRoomMemberHint(userId = UserId(1L), nickname = "Alice"))))
        assertEquals(1, socketCreations)
    }

    @Test
    fun `provider backs off after transient bridge failure`() {
        var now = 10L
        var socketCreations = 0
        val client =
            UdsImageBridgeClient(
                socketFactory = {
                    socketCreations += 1
                    object : BridgeSocket {
                        override val inputStream = ByteArrayInputStream(ByteArray(0))
                        override val outputStream = ByteArrayOutputStream()

                        override fun connect(
                            socketName: String,
                            timeoutMs: Int,
                        ): Nothing = throw IOException("bridge down")

                        override fun connect(socketName: String): Nothing = throw IOException("bridge down")

                        override fun setReadTimeout(timeoutMs: Int) = Unit

                        override fun shutdownOutput() = Unit

                        override fun close() = Unit
                    }
                },
            )
        val provider =
            BridgeLiveRoomMemberSnapshotProvider(
                bridgeClient = client,
                clock = { now },
                retryBackoffMs = 1_000L,
            )

        assertNull(provider.snapshot(ChatId(100L), listOf(LiveRoomMemberHint(userId = UserId(1L), nickname = "Alice"))))
        assertNull(provider.snapshot(ChatId(100L), listOf(LiveRoomMemberHint(userId = UserId(1L), nickname = "Alice"))))
        assertEquals(1, socketCreations)

        now += 1_001L

        assertNull(provider.snapshot(ChatId(100L), listOf(LiveRoomMemberHint(userId = UserId(1L), nickname = "Alice"))))
        assertEquals(2, socketCreations)
    }

    private fun responseSocket(response: ImageBridgeProtocol.ImageBridgeResponse): BridgeSocket {
        val buffer = ByteArrayOutputStream()
        ImageBridgeProtocol.writeFrame(buffer, response)
        return object : BridgeSocket {
            override val inputStream = ByteArrayInputStream(buffer.toByteArray())
            override val outputStream = ByteArrayOutputStream()

            override fun connect(
                socketName: String,
                timeoutMs: Int,
            ) = Unit

            override fun connect(socketName: String) = Unit

            override fun setReadTimeout(timeoutMs: Int) = Unit

            override fun shutdownOutput() = Unit

            override fun close() = Unit
        }
    }
}
