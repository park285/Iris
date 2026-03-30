package party.qwer.iris.http

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
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

internal fun Route.installHealthRoutes(
    authSupport: AuthSupport,
    bridgeHealthProvider: (() -> ImageBridgeHealthResult?)?,
    chatRoomIntrospectProvider: ((Long) -> String?)?,
) {
    get("/health") {
        call.respondText(HEALTH_OK_JSON, ContentType.Application.Json)
    }
    get("/ready") {
        val bridgeHealth = bridgeHealthProvider?.invoke()
        if (bridgeHealth == null || isBridgeReady(bridgeHealth)) {
            call.respondText(READY_OK_JSON, ContentType.Application.Json)
        } else {
            call.respond(HttpStatusCode.ServiceUnavailable, CommonErrorResponse(message = "bridge not ready"))
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
        val result = chatRoomIntrospectProvider?.invoke(chatId) ?: invalidRequest("bridge introspection unavailable")
        call.respondText(result, ContentType.Application.Json)
    }
}
