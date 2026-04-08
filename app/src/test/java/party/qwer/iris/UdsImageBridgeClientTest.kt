package party.qwer.iris

import party.qwer.iris.storage.UserId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UdsImageBridgeClientTest {
    @Test
    fun `warns when bridge token is blank in development mode`() {
        val originalErr = System.err
        val capturedErr = ByteArrayOutputStream()
        try {
            System.setErr(PrintStream(capturedErr, true, Charsets.UTF_8.name()))

            UdsImageBridgeClient(
                bridgeToken = "",
                securityModeRaw = "development",
                socketFactory = { FakeBridgeSocket() },
            )

            val output = capturedErr.toString(Charsets.UTF_8.name())
            assertTrue(output.contains("IRIS_BRIDGE_TOKEN is not configured; bridge requests will be unauthenticated in development mode"))
            assertTrue(output.contains("level=WARN"))
        } finally {
            System.setErr(originalErr)
        }
    }

    @Test
    fun `logs error when bridge token is blank in production mode`() {
        val originalErr = System.err
        val capturedErr = ByteArrayOutputStream()
        try {
            System.setErr(PrintStream(capturedErr, true, Charsets.UTF_8.name()))

            UdsImageBridgeClient(
                bridgeToken = "",
                securityModeRaw = null,
                socketFactory = { FakeBridgeSocket() },
            )

            val output = capturedErr.toString(Charsets.UTF_8.name())
            assertTrue(output.contains("IRIS_BRIDGE_TOKEN must be configured in production mode"))
            assertTrue(output.contains("level=ERROR"))
        } finally {
            System.setErr(originalErr)
        }
    }

    @Test
    fun `falls back to connect without timeout when timed connect is unsupported`() {
        val responseBuffer = ByteArrayOutputStream()
        ImageBridgeProtocol.writeFrame(
            responseBuffer,
            ImageBridgeProtocol.buildSuccessResponse(),
        )
        val fakeSocket =
            FakeBridgeSocket(
                connectWithTimeoutError = UnsupportedOperationException("timeout connect unsupported"),
                input = ByteArrayInputStream(responseBuffer.toByteArray()),
            )

        val client =
            UdsImageBridgeClient(
                socketName = "iris-image-bridge",
                bridgeToken = "bridge-token",
                socketFactory = { fakeSocket },
            )

        val result =
            client.sendImage(
                roomId = 1L,
                imagePaths = listOf("/tmp/test.png"),
                threadId = 2L,
                threadScope = 2,
                requestId = "req-1",
            )

        assertTrue(result.success)
        assertEquals(1, fakeSocket.connectWithTimeoutCalls)
        assertEquals(1, fakeSocket.connectCalls)
        assertEquals(30_000, fakeSocket.readTimeoutMs)
        assertTrue(fakeSocket.outputShutdown)
        val request = ImageBridgeProtocol.readRequestFrame(ByteArrayInputStream(fakeSocket.outputStream.toByteArray()))
        assertEquals("req-1", request.requestId)
        assertEquals(ImageBridgeProtocol.PROTOCOL_VERSION, request.protocolVersion)
        assertEquals("bridge-token", request.token)
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

    @Test
    fun `returns failure when response frame is malformed`() {
        val fakeSocket =
            FakeBridgeSocket(
                input = ByteArrayInputStream(byteArrayOf(0, 0, 0, 0)),
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
        assertTrue(result.error?.contains("bridge protocol failed") == true)
    }

    @Test
    fun `parses bridge health response`() {
        val responseBuffer = ByteArrayOutputStream()
        ImageBridgeProtocol.writeFrame(
            responseBuffer,
            ImageBridgeProtocol.ImageBridgeResponse(
                status = ImageBridgeProtocol.STATUS_OK,
                running = true,
                specReady = false,
                checkedAtEpochMs = 1234L,
                restartCount = 2,
                lastCrashMessage = "bind failed",
                discovery =
                    ImageBridgeProtocol.ImageBridgeDiscovery(
                        installAttempted = true,
                        hooks =
                            listOf(
                                ImageBridgeProtocol.ImageBridgeDiscoveryHook(
                                    name = "bh.c#p",
                                    installed = true,
                                    invocationCount = 4,
                                    lastSeenEpochMs = 99L,
                                    lastSummary = "uris=2",
                                ),
                            ),
                    ),
                capabilities =
                    ImageBridgeProtocol.ImageBridgeCapabilities(
                        inspectChatRoom = ImageBridgeProtocol.ImageBridgeCapability(supported = true, ready = true),
                        snapshotChatRoomMembers =
                            ImageBridgeProtocol.ImageBridgeCapability(
                                supported = true,
                                ready = false,
                                reason = "chatroom resolver unavailable",
                            ),
                    ),
                checks =
                    listOf(
                        ImageBridgeProtocol.ImageBridgeCheck(
                            name = "class bh.c",
                            ok = true,
                        ),
                        ImageBridgeProtocol.ImageBridgeCheck(
                            name = "ChatMediaSender.p(...)",
                            ok = false,
                            detail = "method missing",
                        ),
                    ),
            ),
        )

        val client =
            UdsImageBridgeClient(
                socketFactory = {
                    FakeBridgeSocket(
                        input = ByteArrayInputStream(responseBuffer.toByteArray()),
                    )
                },
            )

        val result = client.queryHealth()

        assertTrue(result.reachable)
        assertTrue(result.running)
        assertFalse(result.specReady)
        assertEquals(1234L, result.checkedAtEpochMs)
        assertEquals(2, result.restartCount)
        assertEquals("bind failed", result.lastCrashMessage)
        assertEquals(2, result.checks.size)
        assertEquals("ChatMediaSender.p(...)", result.checks[1].name)
        assertFalse(result.checks[1].ok)
        assertTrue(result.discoveryInstallAttempted)
        assertEquals(1, result.discoveryHooks.size)
        assertEquals("bh.c#p", result.discoveryHooks.first().name)
        assertEquals("uris=2", result.discoveryHooks.first().lastSummary)
        assertTrue(result.capabilities.inspectChatRoom.ready)
        assertEquals("chatroom resolver unavailable", result.capabilities.snapshotChatRoomMembers.reason)
    }

    @Test
    fun `inspects chatroom through bridge protocol`() {
        val responseBuffer = ByteArrayOutputStream()
        ImageBridgeProtocol.writeFrame(
            responseBuffer,
            ImageBridgeProtocol.ImageBridgeResponse(
                status = ImageBridgeProtocol.STATUS_OK,
                inspectionJson = "{\"chatId\":55}",
            ),
        )
        val fakeSocket = FakeBridgeSocket(input = ByteArrayInputStream(responseBuffer.toByteArray()))
        val client =
            UdsImageBridgeClient(
                bridgeToken = "bridge-token",
                socketFactory = { fakeSocket },
            )

        val result = client.inspectChatRoom(55L)

        assertEquals("{\"chatId\":55}", result)
        val request = ImageBridgeProtocol.readRequestFrame(ByteArrayInputStream(fakeSocket.outputStream.toByteArray()))
        assertEquals(ImageBridgeProtocol.ACTION_INSPECT_CHATROOM, request.action)
        assertEquals(55L, request.roomId)
        assertEquals("bridge-token", request.token)
    }

    @Test
    fun `inspect chatroom fails when bridge omits payload`() {
        val responseBuffer = ByteArrayOutputStream()
        ImageBridgeProtocol.writeFrame(
            responseBuffer,
            ImageBridgeProtocol.ImageBridgeResponse(
                status = ImageBridgeProtocol.STATUS_OK,
            ),
        )
        val client =
            UdsImageBridgeClient(
                socketFactory = {
                    FakeBridgeSocket(
                        input = ByteArrayInputStream(responseBuffer.toByteArray()),
                    )
                },
            )

        val error = runCatching { client.inspectChatRoom(1L) }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertTrue(error.message?.contains("bridge inspection payload missing") == true)
    }

    @Test
    fun `snapshots live chatroom members through bridge protocol`() {
        val responseBuffer = ByteArrayOutputStream()
        ImageBridgeProtocol.writeFrame(
            responseBuffer,
            ImageBridgeProtocol.ImageBridgeResponse(
                status = ImageBridgeProtocol.STATUS_OK,
                memberSnapshot =
                    ImageBridgeProtocol.ChatRoomMembersSnapshot(
                        roomId = 55L,
                        sourcePath = "$.members",
                        sourceClassName = "FakeMember",
                        scannedAtEpochMs = 1234L,
                        members =
                            listOf(
                                ImageBridgeProtocol.ChatRoomMemberSnapshot(
                                    userId = 7L,
                                    nickname = "Alice Updated",
                                    roleCode = 4,
                                    profileImageUrl = "https://example.com/a.png",
                                ),
                            ),
                        selectedPlan =
                            ImageBridgeProtocol.ChatRoomMemberExtractionPlan(
                                containerPath = "$.members",
                                sourceClassName = "FakeMember",
                                userIdPath = "id",
                                nicknamePath = "profile.nickname",
                                fingerprint = "$.members|FakeMember|id|profile.nickname",
                            ),
                        confidence = ImageBridgeProtocol.ChatRoomSnapshotConfidence.MEDIUM,
                        confidenceScore = 420,
                        usedPreferredPlan = true,
                        candidateGap = 180,
                    ),
            ),
        )
        val fakeSocket = FakeBridgeSocket(input = ByteArrayInputStream(responseBuffer.toByteArray()))
        val client =
            UdsImageBridgeClient(
                bridgeToken = "bridge-token",
                socketFactory = { fakeSocket },
            )

        val result =
            client.snapshotChatRoomMembers(
                roomId = 55L,
                expectedMembers =
                    listOf(
                        LiveRoomMemberHint(userId = UserId(7L), nickname = "Alice"),
                        LiveRoomMemberHint(userId = UserId(9L), nickname = "Bob"),
                    ),
                preferredPlan =
                    LiveRoomMemberExtractionPlan(
                        containerPath = "$.members",
                        sourceClassName = "FakeMember",
                        userIdPath = "id",
                        nicknamePath = "profile.nickname",
                        fingerprint = "$.members|FakeMember|id|profile.nickname",
                    ),
            )

        assertEquals(55L, result.chatId.value)
        assertEquals("$.members", result.sourcePath)
        assertEquals(1234L, result.scannedAtEpochMs)
        val member = result.members.getValue(UserId(7L))
        assertEquals(
            "Alice Updated",
            member.nickname,
        )
        assertEquals(LiveSnapshotConfidence.MEDIUM, result.confidence)
        assertEquals(420, result.confidenceScore)
        assertTrue(result.usedPreferredPlan)
        assertEquals(180, result.candidateGap)
        assertEquals("profile.nickname", result.selectedPlan?.nicknamePath)
        val request = ImageBridgeProtocol.readRequestFrame(ByteArrayInputStream(fakeSocket.outputStream.toByteArray()))
        assertEquals(ImageBridgeProtocol.ACTION_SNAPSHOT_CHATROOM_MEMBERS, request.action)
        assertEquals(55L, request.roomId)
        assertEquals(listOf(7L, 9L), request.memberIds)
        assertEquals("Alice", request.memberHints.first { it.userId == 7L }.nickname)
        assertEquals("profile.nickname", request.preferredMemberPlan?.nicknamePath)
        assertEquals("bridge-token", request.token)
    }

    @Test
    fun `snapshot chatroom members fails when bridge omits payload`() {
        val responseBuffer = ByteArrayOutputStream()
        ImageBridgeProtocol.writeFrame(
            responseBuffer,
            ImageBridgeProtocol.ImageBridgeResponse(
                status = ImageBridgeProtocol.STATUS_OK,
            ),
        )
        val client =
            UdsImageBridgeClient(
                socketFactory = {
                    FakeBridgeSocket(
                        input = ByteArrayInputStream(responseBuffer.toByteArray()),
                    )
                },
            )

        val error = runCatching { client.snapshotChatRoomMembers(1L) }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertTrue(error.message?.contains("bridge member snapshot payload missing") == true)
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
