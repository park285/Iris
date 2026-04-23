package party.qwer.iris.http

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import party.qwer.iris.ConfigManager
import party.qwer.iris.model.ImageBridgeCapabilities
import party.qwer.iris.model.ImageBridgeCapability
import party.qwer.iris.model.ImageBridgeDiscoveryHook
import party.qwer.iris.model.ImageBridgeHealthResult
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HealthRoutesTest {
    private val serverJson =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

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
                        bridgeRequired = true,
                    ),
            )

        assertEquals("config not ready: inbound signing secret not configured", reason)
    }

    @Test
    fun `readiness ignores missing bridge token when bridge not required`() {
        val reason =
            readinessFailureReason(
                bridgeHealth = null,
                configReadiness =
                    RuntimeConfigReadiness(
                        inboundSigningSecretConfigured = true,
                        outboundWebhookTokenConfigured = true,
                        botControlTokenConfigured = true,
                        bridgeTokenConfigured = false,
                        defaultWebhookEndpointConfigured = true,
                        bridgeRequired = false,
                    ),
            )

        assertNull(reason)
    }

    @Test
    fun `runtime readiness does not require default webhook endpoint when secrets are configured`() {
        val reason =
            RuntimeConfigReadiness(
                inboundSigningSecretConfigured = true,
                outboundWebhookTokenConfigured = true,
                botControlTokenConfigured = true,
                bridgeTokenConfigured = false,
                defaultWebhookEndpointConfigured = false,
                bridgeRequired = false,
            ).failureReason()

        assertNull(reason)
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
    fun `configured bridge deployments still fail readiness on unhealthy bridge`() {
        val reason =
            readinessFailureReason(
                bridgeHealth =
                    healthResult(
                        running = false,
                        requiredHookStates = REQUIRED_HOOK_NAMES.associateWith { true },
                    ),
                configReadiness =
                    RuntimeConfigReadiness(
                        inboundSigningSecretConfigured = true,
                        outboundWebhookTokenConfigured = true,
                        botControlTokenConfigured = true,
                        bridgeTokenConfigured = true,
                        defaultWebhookEndpointConfigured = true,
                        bridgeRequired = true,
                    ),
            )

        assertEquals("bridge not ready", reason)
    }

    @Test
    fun `readiness ignores bridge health when bridge not required`() {
        val reason =
            readinessFailureReason(
                bridgeHealth =
                    healthResult(
                        reachable = false,
                        requiredHookStates = REQUIRED_HOOK_NAMES.associateWith { true },
                    ),
                configReadiness = RuntimeConfigReadiness.allConfigured(bridgeRequired = false),
            )

        assertNull(reason)
    }

    @Test
    fun `ready ignores snapshot chatroom members capability when hooks are ready`() {
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

    @Test
    fun `ready route treats malformed override as auto and requires env fallback bridge token deployments`() =
        testApplication {
            val configDir = Files.createTempDirectory("iris-ready-route-bridge-auto").toFile()
            val configPath = configDir.resolve("config.json")
            configPath.writeText(
                """
                {
                  "endpoint":"https://example.com/webhook",
                  "inboundSigningSecret":"inbound-secret",
                  "outboundWebhookToken":"outbound-secret",
                  "botControlToken":"control-secret"
                }
                """.trimIndent(),
            )
            val manager =
                ConfigManager(
                    configPath = configPath.absolutePath,
                    env =
                        mapOf(
                            "IRIS_BRIDGE_TOKEN" to "env-bridge-secret",
                            "IRIS_REQUIRE_BRIDGE" to "maybe",
                        ),
                )

            try {
                application {
                    install(ContentNegotiation) {
                        json(serverJson)
                    }
                    routing {
                        installHealthRoutes(
                            authSupport = AuthSupport(party.qwer.iris.RequestAuthenticator(), manager),
                            bridgeHealthProvider = {
                                healthResult(
                                    running = false,
                                    requiredHookStates = REQUIRED_HOOK_NAMES.associateWith { true },
                                )
                            },
                            configReadinessProvider = manager::runtimeConfigReadiness,
                            chatRoomIntrospectProvider = null,
                        )
                    }
                }

                val response = client.get("/ready")

                assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
                assertTrue(response.bodyAsText().contains("not ready"))
                assertFalse(response.bodyAsText().contains("bridge not ready"))
            } finally {
                configDir.deleteRecursively()
            }
        }

    @Test
    fun `ready route exposes raw reason when verbose is enabled`() =
        testApplication {
            val configDir = Files.createTempDirectory("iris-ready-route-verbose").toFile()
            val configPath = configDir.resolve("config.json")
            configPath.writeText(
                """
                {
                  "endpoint":"https://example.com/webhook",
                  "inboundSigningSecret":"inbound-secret",
                  "outboundWebhookToken":"outbound-secret",
                  "botControlToken":"control-secret"
                }
                """.trimIndent(),
            )
            val manager =
                ConfigManager(
                    configPath = configPath.absolutePath,
                    env = mapOf("IRIS_BRIDGE_TOKEN" to "env-bridge-secret"),
                )

            try {
                application {
                    install(ContentNegotiation) {
                        json(serverJson)
                    }
                    routing {
                        installHealthRoutes(
                            authSupport = AuthSupport(party.qwer.iris.RequestAuthenticator(), manager),
                            bridgeHealthProvider = {
                                healthResult(
                                    running = false,
                                    requiredHookStates = REQUIRED_HOOK_NAMES.associateWith { true },
                                )
                            },
                            configReadinessProvider = manager::runtimeConfigReadiness,
                            chatRoomIntrospectProvider = null,
                            readyVerbose = true,
                        )
                    }
                }

                val response = client.get("/ready")

                assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
                assertTrue(response.bodyAsText().contains("bridge not ready"))
            } finally {
                configDir.deleteRecursively()
            }
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
