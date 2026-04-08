package party.qwer.iris.http

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import party.qwer.iris.MemberNicknameDiagnostics
import party.qwer.iris.internalServerFailure
import party.qwer.iris.invalidRequest
import party.qwer.iris.model.CommonErrorResponse
import party.qwer.iris.model.ImageBridgeHealthResult

private const val HEALTH_OK_JSON = """{"status":"ok"}"""
private const val READY_OK_JSON = """{"status":"ready"}"""

private val REQUIRED_DISCOVERY_HOOKS =
    setOf(
        "ChatMediaSender#sendSingle",
        "ChatMediaSender#sendMultiple",
        "ReplyMarkdown#ingress",
        "ReplyMarkdown#reuseIntent",
        "ReplyMarkdown#requestDispatch",
    )

internal sealed interface RuntimeBootstrapState {
    data object Ready : RuntimeBootstrapState

    data class Blocked(
        val reason: String,
    ) : RuntimeBootstrapState
}

internal data class RuntimeConfigReadiness(
    val inboundSigningSecretConfigured: Boolean,
    val outboundWebhookTokenConfigured: Boolean,
    val botControlTokenConfigured: Boolean,
    val bridgeTokenConfigured: Boolean,
    val defaultWebhookEndpointConfigured: Boolean,
) {
    fun bootstrapState(): RuntimeBootstrapState =
        when {
            !inboundSigningSecretConfigured -> RuntimeBootstrapState.Blocked("inbound signing secret not configured")
            !outboundWebhookTokenConfigured -> RuntimeBootstrapState.Blocked("outbound webhook token not configured")
            !botControlTokenConfigured -> RuntimeBootstrapState.Blocked("bot control token not configured")
            !bridgeTokenConfigured -> RuntimeBootstrapState.Blocked("bridge token not configured")
            !defaultWebhookEndpointConfigured -> RuntimeBootstrapState.Blocked("webhook endpoint not configured")
            else -> RuntimeBootstrapState.Ready
        }

    fun failureReason(): String? =
        when (val state = bootstrapState()) {
            RuntimeBootstrapState.Ready -> null
            is RuntimeBootstrapState.Blocked -> state.reason
        }

    companion object {
        fun allConfigured(): RuntimeConfigReadiness =
            RuntimeConfigReadiness(
                inboundSigningSecretConfigured = true,
                outboundWebhookTokenConfigured = true,
                botControlTokenConfigured = true,
                bridgeTokenConfigured = true,
                defaultWebhookEndpointConfigured = true,
            )
    }
}

internal fun isBridgeReady(health: ImageBridgeHealthResult): Boolean {
    val hooksByName = health.discoveryHooks.associateBy { it.name }
    val requiredHooksReady =
        REQUIRED_DISCOVERY_HOOKS.all { hookName ->
            hooksByName[hookName]?.installed == true
        }
    val snapshotCapabilityReady = health.capabilities.snapshotChatRoomMembers.ready
    return health.reachable &&
        health.running &&
        health.specReady &&
        health.discoveryInstallAttempted &&
        requiredHooksReady &&
        snapshotCapabilityReady
}

internal fun readinessFailureReason(
    bridgeHealth: ImageBridgeHealthResult?,
    configReadiness: RuntimeConfigReadiness? = null,
): String? {
    when (val bootstrapState = configReadiness?.bootstrapState()) {
        null,
        RuntimeBootstrapState.Ready,
        -> Unit

        is RuntimeBootstrapState.Blocked -> {
            return "config not ready: ${bootstrapState.reason}"
        }
    }
    if (bridgeHealth != null && !isBridgeReady(bridgeHealth)) {
        val capabilityReason = bridgeHealth.capabilities.snapshotChatRoomMembers.reason
        return capabilityReason?.let { "bridge not ready: $it" } ?: "bridge not ready"
    }
    return null
}

internal fun Route.installHealthRoutes(
    authSupport: AuthSupport,
    bridgeHealthProvider: (() -> ImageBridgeHealthResult?)?,
    configReadinessProvider: (() -> RuntimeConfigReadiness)? = null,
    chatRoomIntrospectProvider: ((Long) -> String)?,
    memberNicknameDiagnosticsProvider: ((Long) -> MemberNicknameDiagnostics?)? = null,
) {
    get("/health") {
        call.respondText(HEALTH_OK_JSON, ContentType.Application.Json)
    }
    get("/ready") {
        val bridgeHealth = bridgeHealthProvider?.invoke()
        val failureReason = readinessFailureReason(bridgeHealth, configReadinessProvider?.invoke())
        if (failureReason == null) {
            call.respondText(READY_OK_JSON, ContentType.Application.Json)
        } else {
            call.respond(HttpStatusCode.ServiceUnavailable, CommonErrorResponse(message = failureReason))
        }
    }
    get("/diagnostics/bridge") {
        if (!authSupport.requireBotControlSignature(call, method = "GET")) return@get
        val bridgeHealth = bridgeHealthProvider?.invoke() ?: invalidRequest("bridge health unavailable")
        call.respond(bridgeHealth)
    }
    get("/diagnostics/chatroom-fields/{chatId}") {
        if (!authSupport.requireBotControlSignature(call, method = "GET")) return@get
        val chatId = call.parameters["chatId"]?.toLongOrNull() ?: invalidRequest("chatId must be a number")
        val provider = chatRoomIntrospectProvider ?: invalidRequest("bridge introspection unavailable")
        val result =
            runCatching { provider(chatId) }
                .getOrElse { error ->
                    internalServerFailure(error.message ?: "bridge introspection failed")
                }
        call.respondText(result, ContentType.Application.Json)
    }
    get("/diagnostics/chatroom-members/{chatId}") {
        if (!authSupport.requireBotControlSignature(call, method = "GET")) return@get
        val chatId = call.parameters["chatId"]?.toLongOrNull() ?: invalidRequest("chatId must be a number")
        val provider = memberNicknameDiagnosticsProvider ?: invalidRequest("member diagnostics unavailable")
        val result = provider(chatId) ?: invalidRequest("member diagnostics unavailable")
        call.respond(result)
    }
}
