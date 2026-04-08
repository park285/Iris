package party.qwer.iris

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import party.qwer.iris.http.SseEventEnvelope
import party.qwer.iris.http.initialSseFrames
import party.qwer.iris.http.isBridgeReady
import party.qwer.iris.model.ImageBridgeCapabilities
import party.qwer.iris.model.ImageBridgeCapability
import party.qwer.iris.model.ImageBridgeDiscoveryHook
import party.qwer.iris.model.ImageBridgeHealthResult
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IrisServerNettyTransportTest {
    @Test
    fun `enforceNettyNioTransport sets noNative property when absent`() {
        val previous = System.getProperty(NETTY_NO_NATIVE_PROPERTY)
        try {
            System.clearProperty(NETTY_NO_NATIVE_PROPERTY)

            val changed = enforceNettyNioTransport()

            assertTrue(changed)
            assertEquals("true", System.getProperty(NETTY_NO_NATIVE_PROPERTY))
        } finally {
            restoreProperty(previous)
        }
    }

    @Test
    fun `enforceNettyNioTransport keeps existing true property`() {
        val previous = System.getProperty(NETTY_NO_NATIVE_PROPERTY)
        try {
            System.setProperty(NETTY_NO_NATIVE_PROPERTY, "true")

            val changed = enforceNettyNioTransport()

            assertFalse(changed)
            assertEquals("true", System.getProperty(NETTY_NO_NATIVE_PROPERTY))
        } finally {
            restoreProperty(previous)
        }
    }

    @Test
    fun `bridge ready ignores optional discovery hook failures`() {
        val health =
            ImageBridgeHealthResult(
                reachable = true,
                running = true,
                specReady = true,
                restartCount = 0,
                discoveryInstallAttempted = true,
                capabilities =
                    ImageBridgeCapabilities(
                        inspectChatRoom = ImageBridgeCapability(supported = true, ready = true),
                        snapshotChatRoomMembers = ImageBridgeCapability(supported = true, ready = true),
                    ),
                discoveryHooks =
                    listOf(
                        ImageBridgeDiscoveryHook(
                            name = "MasterDatabase#roomDao",
                            installed = false,
                            installError = "abstract method, skipped",
                            invocationCount = 0,
                        ),
                        ImageBridgeDiscoveryHook(
                            name = "ChatMediaSender#sendSingle",
                            installed = true,
                            invocationCount = 1,
                        ),
                        ImageBridgeDiscoveryHook(
                            name = "ChatMediaSender#sendMultiple",
                            installed = true,
                            invocationCount = 1,
                        ),
                        ImageBridgeDiscoveryHook(
                            name = "ReplyMarkdown#ingress",
                            installed = true,
                            invocationCount = 1,
                        ),
                        ImageBridgeDiscoveryHook(
                            name = "ReplyMarkdown#reuseIntent",
                            installed = true,
                            invocationCount = 0,
                        ),
                        ImageBridgeDiscoveryHook(
                            name = "ReplyMarkdown#requestDispatch",
                            installed = true,
                            invocationCount = 1,
                        ),
                    ),
            )

        assertTrue(isBridgeReady(health))
    }

    @Test
    fun `bridge ready requires send discovery hooks`() {
        val health =
            ImageBridgeHealthResult(
                reachable = true,
                running = true,
                specReady = true,
                restartCount = 0,
                discoveryInstallAttempted = true,
                capabilities =
                    ImageBridgeCapabilities(
                        inspectChatRoom = ImageBridgeCapability(supported = true, ready = true),
                        snapshotChatRoomMembers = ImageBridgeCapability(supported = true, ready = true),
                    ),
                discoveryHooks =
                    listOf(
                        ImageBridgeDiscoveryHook(
                            name = "ChatMediaSender#sendSingle",
                            installed = true,
                            invocationCount = 1,
                        ),
                        ImageBridgeDiscoveryHook(
                            name = "ReplyMarkdown#ingress",
                            installed = true,
                            invocationCount = 1,
                        ),
                        ImageBridgeDiscoveryHook(
                            name = "ReplyMarkdown#reuseIntent",
                            installed = true,
                            invocationCount = 0,
                        ),
                        ImageBridgeDiscoveryHook(
                            name = "ReplyMarkdown#requestDispatch",
                            installed = true,
                            invocationCount = 1,
                        ),
                    ),
            )

        assertFalse(isBridgeReady(health))
    }

    @Test
    fun `bridge ready requires markdown discovery hooks`() {
        val health =
            ImageBridgeHealthResult(
                reachable = true,
                running = true,
                specReady = true,
                restartCount = 0,
                discoveryInstallAttempted = true,
                capabilities =
                    ImageBridgeCapabilities(
                        inspectChatRoom = ImageBridgeCapability(supported = true, ready = true),
                        snapshotChatRoomMembers = ImageBridgeCapability(supported = true, ready = true),
                    ),
                discoveryHooks =
                    listOf(
                        ImageBridgeDiscoveryHook(
                            name = "ChatMediaSender#sendSingle",
                            installed = true,
                            invocationCount = 1,
                        ),
                        ImageBridgeDiscoveryHook(
                            name = "ChatMediaSender#sendMultiple",
                            installed = true,
                            invocationCount = 1,
                        ),
                        ImageBridgeDiscoveryHook(
                            name = "ReplyMarkdown#ingress",
                            installed = true,
                            invocationCount = 1,
                        ),
                        ImageBridgeDiscoveryHook(
                            name = "ReplyMarkdown#requestDispatch",
                            installed = true,
                            invocationCount = 1,
                        ),
                    ),
            )

        assertFalse(isBridgeReady(health))
    }

    @Test
    fun `runtime server enables http2 and h2c by default`() {
        assertTrue(IrisServer.runtimeHttp2Enabled())
        assertTrue(IrisServer.runtimeH2cEnabled())
    }

    @Test
    fun `runtime server accepts h2c prior knowledge health request`() {
        val configDir = Files.createTempDirectory("iris-server-h2c-test")
        val configPath = configDir.resolve("config.json")
        val port = reservePort()
        configPath.toFile().writeText(
            """
            {
              "botHttpPort": $port,
              "inboundSigningSecret": "inbound",
              "outboundWebhookToken": "outbound",
              "botControlToken": "control",
              "bridgeToken": "bridge"
            }
            """.trimIndent(),
        )

        val server =
            IrisServer(
                configManager = ConfigManager(configPath = configPath.toString()),
                notificationReferer = "ref",
                messageSender = noopMessageSender,
                bindHost = "127.0.0.1",
            )
        val client =
            OkHttpClient
                .Builder()
                .protocols(listOf(Protocol.H2_PRIOR_KNOWLEDGE))
                .readTimeout(5, TimeUnit.SECONDS)
                .build()

        try {
            server.startServer()
            val response =
                client
                    .newCall(
                        Request
                            .Builder()
                            .url("http://127.0.0.1:$port/health")
                            .get()
                            .build(),
                    ).execute()

            assertEquals(200, response.code)
            assertEquals("""{"status":"ok"}""", response.body.string())
        } finally {
            runCatching { server.stopServer() }
            configPath.deleteIfExists()
            configDir.deleteIfExists()
        }
    }

    @Test
    fun `initial sse frames include keepalive comment before replay`() {
        val frames =
            initialSseFrames(
                listOf(
                    SseEventEnvelope(
                        id = 2L,
                        eventType = "member_event",
                        payload = "{\"type\":\"member_event\"}",
                        createdAtMs = 0L,
                    ),
                ),
            )

        assertTrue(frames.startsWith(": connected\n\n"))
        assertTrue(frames.contains("id: 2\nevent: member_event\ndata: {\"type\":\"member_event\"}\n\n"))
    }

    private fun restoreProperty(previous: String?) {
        if (previous == null) {
            System.clearProperty(NETTY_NO_NATIVE_PROPERTY)
        } else {
            System.setProperty(NETTY_NO_NATIVE_PROPERTY, previous)
        }
    }

    private fun reservePort(): Int =
        java.net.ServerSocket(0).use { socket ->
            socket.localPort
        }

    private val noopMessageSender =
        object : MessageSender {
            override suspend fun sendMessageSuspend(
                referer: String,
                chatId: Long,
                msg: String,
                threadId: Long?,
                threadScope: Int?,
                requestId: String?,
            ): ReplyAdmissionResult = ReplyAdmissionResult(ReplyAdmissionStatus.ACCEPTED)

            override suspend fun sendNativeMultiplePhotosHandlesSuspend(
                room: Long,
                imageHandles: List<VerifiedImagePayloadHandle>,
                threadId: Long?,
                threadScope: Int?,
                requestId: String?,
            ): ReplyAdmissionResult =
                try {
                    ReplyAdmissionResult(ReplyAdmissionStatus.ACCEPTED)
                } finally {
                    imageHandles.forEach { handle ->
                        runCatching { handle.close() }
                    }
                }

            override suspend fun sendTextShareSuspend(
                room: Long,
                msg: String,
                requestId: String?,
            ): ReplyAdmissionResult = ReplyAdmissionResult(ReplyAdmissionStatus.ACCEPTED)

            override suspend fun sendReplyMarkdownSuspend(
                room: Long,
                msg: String,
                threadId: Long?,
                threadScope: Int?,
                requestId: String?,
            ): ReplyAdmissionResult = ReplyAdmissionResult(ReplyAdmissionStatus.ACCEPTED)
        }
}
