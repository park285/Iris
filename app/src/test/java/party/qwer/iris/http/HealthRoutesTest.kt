package party.qwer.iris.http

import party.qwer.iris.model.ImageBridgeCapabilities
import party.qwer.iris.model.ImageBridgeCapability
import party.qwer.iris.model.ImageBridgeDiscoveryHook
import party.qwer.iris.model.ImageBridgeHealthResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HealthRoutesTest {
    @Test
    fun `returns true when all required hooks installed`() {
        val health = healthResult(requiredHookStates = REQUIRED_HOOK_NAMES.associateWith { true })

        assertTrue(isBridgeReady(health))
    }

    @Test
    fun `returns false when required hook missing`() {
        val hookStates = REQUIRED_HOOK_NAMES.associateWith { true } - "ReplyMarkdown#requestDispatch"
        val health = healthResult(requiredHookStates = hookStates)

        assertFalse(isBridgeReady(health))
    }

    @Test
    fun `returns false when not reachable`() {
        val health =
            healthResult(
                reachable = false,
                requiredHookStates = REQUIRED_HOOK_NAMES.associateWith { true },
            )

        assertFalse(isBridgeReady(health))
    }

    @Test
    fun `ignores optional hooks`() {
        val health =
            healthResult(
                requiredHookStates = REQUIRED_HOOK_NAMES.associateWith { true },
                optionalHooks = listOf(discoveryHook(name = "Optional#hook", installed = false)),
            )

        assertTrue(isBridgeReady(health))
    }

    @Test
    fun `readiness failure prioritizes bootstrap config before bridge state`() {
        val reason =
            readinessFailureReason(
                bridgeHealth = healthResult(requiredHookStates = REQUIRED_HOOK_NAMES.associateWith { true }),
                configReadiness =
                    RuntimeConfigReadiness(
                        inboundSigningSecretConfigured = false,
                        outboundWebhookTokenConfigured = true,
                        botControlTokenConfigured = true,
                        bridgeTokenConfigured = true,
                        defaultWebhookEndpointConfigured = true,
                    ),
            )

        assertEquals("config not ready: inbound signing secret not configured", reason)
    }

    @Test
    fun `readiness failure reports bridge when config is complete`() {
        val reason =
            readinessFailureReason(
                bridgeHealth =
                    healthResult(
                        reachable = false,
                        requiredHookStates = REQUIRED_HOOK_NAMES.associateWith { true },
                    ),
                configReadiness = RuntimeConfigReadiness.allConfigured(),
            )

        assertEquals("bridge not ready", reason)
    }

    @Test
    fun `ready fails when snapshot chatroom members capability is missing`() {
        val reason =
            readinessFailureReason(
                bridgeHealth =
                    healthResult(
                        requiredHookStates = REQUIRED_HOOK_NAMES.associateWith { true },
                        capabilities =
                            ImageBridgeCapabilities(
                                inspectChatRoom = ImageBridgeCapability(supported = true, ready = true),
                                snapshotChatRoomMembers =
                                    ImageBridgeCapability(
                                        supported = true,
                                        ready = false,
                                        reason = "chatroom resolver unavailable",
                                    ),
                            ),
                    ),
                configReadiness = RuntimeConfigReadiness.allConfigured(),
            )

        assertEquals("bridge not ready: chatroom resolver unavailable", reason)
    }

    @Test
    fun `ready succeeds when snapshot chatroom members capability is ready`() {
        val reason =
            readinessFailureReason(
                bridgeHealth =
                    healthResult(
                        requiredHookStates = REQUIRED_HOOK_NAMES.associateWith { true },
                        capabilities =
                            ImageBridgeCapabilities(
                                inspectChatRoom = ImageBridgeCapability(supported = true, ready = true),
                                snapshotChatRoomMembers = ImageBridgeCapability(supported = true, ready = true),
                            ),
                    ),
                configReadiness = RuntimeConfigReadiness.allConfigured(),
            )

        assertNull(reason)
    }

    @Test
    fun `readiness failure returns null when config and bridge are ready`() {
        val reason =
            readinessFailureReason(
                bridgeHealth = healthResult(requiredHookStates = REQUIRED_HOOK_NAMES.associateWith { true }),
                configReadiness = RuntimeConfigReadiness.allConfigured(),
            )

        assertNull(reason)
    }

    private fun healthResult(
        reachable: Boolean = true,
        running: Boolean = true,
        specReady: Boolean = true,
        discoveryInstallAttempted: Boolean = true,
        requiredHookStates: Map<String, Boolean>,
        optionalHooks: List<ImageBridgeDiscoveryHook> = emptyList(),
        capabilities: ImageBridgeCapabilities =
            ImageBridgeCapabilities(
                inspectChatRoom = ImageBridgeCapability(supported = true, ready = true),
                snapshotChatRoomMembers = ImageBridgeCapability(supported = true, ready = true),
            ),
    ): ImageBridgeHealthResult =
        ImageBridgeHealthResult(
            reachable = reachable,
            running = running,
            specReady = specReady,
            restartCount = 0,
            discoveryInstallAttempted = discoveryInstallAttempted,
            discoveryHooks = requiredHookStates.map { (name, installed) -> discoveryHook(name, installed) } + optionalHooks,
            capabilities = capabilities,
        )

    private fun discoveryHook(
        name: String,
        installed: Boolean,
    ): ImageBridgeDiscoveryHook =
        ImageBridgeDiscoveryHook(
            name = name,
            installed = installed,
            invocationCount = 0,
        )

    private companion object {
        val REQUIRED_HOOK_NAMES =
            setOf(
                "ChatMediaSender#sendSingle",
                "ChatMediaSender#sendMultiple",
                "ReplyMarkdown#ingress",
                "ReplyMarkdown#reuseIntent",
                "ReplyMarkdown#requestDispatch",
            )
    }
}
