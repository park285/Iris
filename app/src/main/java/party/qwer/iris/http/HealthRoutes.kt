package party.qwer.iris.http

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import party.qwer.iris.ImageBridgeResult
import party.qwer.iris.IrisLogger
import party.qwer.iris.MemberNicknameDiagnostics
import party.qwer.iris.internalServerFailure
import party.qwer.iris.invalidRequest
import party.qwer.iris.model.ChatRoomOpenResponse
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
    val bridgeRequired: Boolean = true,
) {
    fun bootstrapState(): RuntimeBootstrapState =
        when {
            !inboundSigningSecretConfigured -> RuntimeBootstrapState.Blocked("inbound signing secret not configured")
            !outboundWebhookTokenConfigured -> RuntimeBootstrapState.Blocked("outbound webhook token not configured")
            !botControlTokenConfigured -> RuntimeBootstrapState.Blocked("bot control token not configured")
            bridgeRequired && !bridgeTokenConfigured -> RuntimeBootstrapState.Blocked("bridge token not configured")
            else -> RuntimeBootstrapState.Ready
        }

    fun failureReason(): String? =
        when (val state = bootstrapState()) {
            RuntimeBootstrapState.Ready -> null
            is RuntimeBootstrapState.Blocked -> state.reason
        }

    companion object {
        fun allConfigured(bridgeRequired: Boolean = true): RuntimeConfigReadiness =
            RuntimeConfigReadiness(
                inboundSigningSecretConfigured = true,
                outboundWebhookTokenConfigured = true,
                botControlTokenConfigured = true,
                bridgeTokenConfigured = true,
                defaultWebhookEndpointConfigured = true,
                bridgeRequired = bridgeRequired,
            )
    }
}

internal fun isBridgeReady(health: ImageBridgeHealthResult): Boolean {
    val hooksByName = health.discoveryHooks.associateBy { it.name }
    val requiredHooksReady =
        REQUIRED_DISCOVERY_HOOKS.all { hookName ->
            hooksByName[hookName]?.installed == true
        }
    return health.reachable &&
        health.running &&
        health.specReady &&
        health.discoveryInstallAttempted &&
        requiredHooksReady
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
    val bridgeRequired = configReadiness?.bridgeRequired == true
    if (bridgeRequired && bridgeHealth != null && !isBridgeReady(bridgeHealth)) {
        return "bridge not ready"
    }
    return null
}

internal fun readyErrorMessage(
    rawReason: String,
    verbose: Boolean,
): String = if (verbose) rawReason else "not ready"

internal fun Route.installHealthRoutes(
    authSupport: AuthSupport,
    bridgeHealthProvider: (() -> ImageBridgeHealthResult?)?,
    configReadinessProvider: (() -> RuntimeConfigReadiness)? = null,
    chatRoomIntrospectProvider: ((Long) -> String)?,
    chatRoomOpenProvider: ((Long) -> ImageBridgeResult)? = null,
    memberNicknameDiagnosticsProvider: ((Long) -> MemberNicknameDiagnostics?)? = null,
    readyVerbose: Boolean = false,
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
            if (!readyVerbose) {
                IrisLogger.warn("[HealthRoutes] /ready failed: $failureReason")
            }
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                CommonErrorResponse(message = readyErrorMessage(failureReason, readyVerbose)),
            )
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
    post("/diagnostics/chatroom-open/{chatId}") {
        if (!authSupport.requireBotControlSignature(call, method = "POST")) return@post
        val chatId = call.parameters["chatId"]?.toLongOrNull() ?: invalidRequest("chatId must be a number")
        val provider = chatRoomOpenProvider ?: invalidRequest("chatroom opener unavailable")
        val result =
            runCatching { provider(chatId) }
                .getOrElse { error ->
                    internalServerFailure(error.message ?: "chatroom open failed")
                }
        if (!result.success) {
            internalServerFailure(result.error ?: "chatroom open failed")
        }
        call.respond(ChatRoomOpenResponse(chatId = chatId, opened = true))
    }
    get("/diagnostics/chatroom-members/{chatId}") {
        if (!authSupport.requireBotControlSignature(call, method = "GET")) return@get
        val chatId = call.parameters["chatId"]?.toLongOrNull() ?: invalidRequest("chatId must be a number")
        val provider = memberNicknameDiagnosticsProvider ?: invalidRequest("member diagnostics unavailable")
        val result = provider(chatId) ?: invalidRequest("member diagnostics unavailable")
        call.respond(result)
    }
}
