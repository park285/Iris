package party.qwer.iris.http

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.json.Json
import party.qwer.iris.ApiRequestException
import party.qwer.iris.ConfigManager
import party.qwer.iris.applyConfigUpdate
import party.qwer.iris.model.ConfigRequest

private const val MAX_CONFIG_REQUEST_BODY_BYTES = 64 * 1024

internal fun Route.installConfigRoutes(
    authSupport: AuthSupport,
    configManager: ConfigManager,
    serverJson: Json,
) {
    route("/config") {
        get {
            if (!authSupport.requireBotToken(call, method = "GET")) return@get
            call.respond(configManager.configResponse())
        }
        post("{name}") {
            readProtectedBody(call, MAX_CONFIG_REQUEST_BODY_BYTES).use { bodyResult ->
                if (!authSupport.requireBotToken(call, method = "POST", bodySha256Hex = bodyResult.sha256Hex)) {
                    return@post
                }
                val name = call.parameters["name"] ?: throw ApiRequestException("missing config name")
                val request = bodyResult.decodeJson(serverJson, ConfigRequest.serializer())
                val updateOutcome = applyConfigUpdate(configManager, name, request)
                if (!configManager.saveConfigNow()) {
                    throw ApiRequestException("failed to persist config update", HttpStatusCode.InternalServerError)
                }
                call.respond(
                    configManager.configUpdateResponse(
                        name = updateOutcome.name,
                        persisted = true,
                        applied = updateOutcome.applied,
                        requiresRestart = updateOutcome.requiresRestart,
                    ),
                )
            }
        }
    }
}
