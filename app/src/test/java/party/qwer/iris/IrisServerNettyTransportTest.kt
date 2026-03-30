package party.qwer.iris

import party.qwer.iris.http.SseEventEnvelope
import party.qwer.iris.http.initialSseFrames
import party.qwer.iris.http.isBridgeReady
import party.qwer.iris.model.ImageBridgeDiscoveryHook
import party.qwer.iris.model.ImageBridgeHealthResult
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
    fun `runtime server disables http2 and h2c by default`() {
        assertFalse(IrisServer.runtimeHttp2Enabled())
        assertFalse(IrisServer.runtimeH2cEnabled())
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
}
