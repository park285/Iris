package party.qwer.iris.http

import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.json.Json
import party.qwer.iris.MemberRepository
import party.qwer.iris.internalServerFailure
import party.qwer.iris.invalidRequest
import party.qwer.iris.model.QueryMemberStatsRequest
import party.qwer.iris.model.QueryRecentThreadsRequest
import party.qwer.iris.model.QueryRoomSummaryRequest

private const val MAX_QUERY_REQUEST_BODY_BYTES = 256 * 1024

internal fun Route.installQueryRoutes(
    authSupport: AuthSupport,
    serverJson: Json,
    memberRepo: MemberRepository?,
) {
    val repo = memberRepo

    post("/query/room-summary") {
        val availableRepo = repo ?: internalServerFailure("member repository unavailable")
        readProtectedBody(call, MAX_QUERY_REQUEST_BODY_BYTES).use { rawBody ->
            if (!authSupport.requireBotControlSignature(call, method = "POST", bodySha256Hex = rawBody.sha256Hex)) {
                return@post
            }
            val request = rawBody.decodeJson(serverJson, QueryRoomSummaryRequest.serializer())
            val summary = availableRepo.roomSummary(request.chatId) ?: invalidRequest("room not found")
            call.respond(summary)
        }
    }

    post("/query/member-stats") {
        val availableRepo = repo ?: internalServerFailure("member repository unavailable")
        readProtectedBody(call, MAX_QUERY_REQUEST_BODY_BYTES).use { rawBody ->
            if (!authSupport.requireBotControlSignature(call, method = "POST", bodySha256Hex = rawBody.sha256Hex)) {
                return@post
            }
            val request = rawBody.decodeJson(serverJson, QueryMemberStatsRequest.serializer())
            call.respond(
                availableRepo.roomStats(
                    chatId = request.chatId,
                    period = request.period,
                    limit = request.limit,
                    minMessages = request.minMessages,
                ),
            )
        }
    }

    post("/query/recent-threads") {
        val availableRepo = repo ?: internalServerFailure("member repository unavailable")
        readProtectedBody(call, MAX_QUERY_REQUEST_BODY_BYTES).use { rawBody ->
            if (!authSupport.requireBotControlSignature(call, method = "POST", bodySha256Hex = rawBody.sha256Hex)) {
                return@post
            }
            val request = rawBody.decodeJson(serverJson, QueryRecentThreadsRequest.serializer())
            call.respond(availableRepo.listThreads(request.chatId))
        }
    }
}
