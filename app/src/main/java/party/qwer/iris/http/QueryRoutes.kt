package party.qwer.iris.http

import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.json.Json
import party.qwer.iris.MemberRepository
import party.qwer.iris.internalServerFailure
import party.qwer.iris.invalidRequest
import party.qwer.iris.model.QueryMemberStatsRequest
import party.qwer.iris.model.QueryRecentMessagesRequest
import party.qwer.iris.model.QueryRecentThreadsRequest
import party.qwer.iris.model.QueryRoomSummaryRequest

private const val MAX_QUERY_REQUEST_BODY_BYTES = 256 * 1024

internal fun Route.installQueryRoutes(
    authSupport: AuthSupport,
    serverJson: Json,
    memberRepo: MemberRepository?,
    protectedBodyReader: ProtectedBodyReader = ::readProtectedBody,
) {
    val repo = memberRepo

    post("/query/room-summary") {
        val availableRepo = repo ?: internalServerFailure("member repository unavailable")
        if (
            !withVerifiedProtectedBody(
                call = call,
                maxBodyBytes = MAX_QUERY_REQUEST_BODY_BYTES,
                bodyReader = protectedBodyReader,
                precheck = { authSupport.precheckBotControlSignature(call, method = "POST") },
                finalize = { precheck, actualBodySha256Hex -> authSupport.finalizeSignature(call, precheck, actualBodySha256Hex) },
            ) { rawBody ->
                val request = rawBody.decodeJson(serverJson, QueryRoomSummaryRequest.serializer())
                val summary = availableRepo.roomSummary(request.chatId) ?: invalidRequest("room not found")
                call.respond(summary)
            }
        ) {
            return@post
        }
    }

    post("/query/member-stats") {
        val availableRepo = repo ?: internalServerFailure("member repository unavailable")
        if (
            !withVerifiedProtectedBody(
                call = call,
                maxBodyBytes = MAX_QUERY_REQUEST_BODY_BYTES,
                bodyReader = protectedBodyReader,
                precheck = { authSupport.precheckBotControlSignature(call, method = "POST") },
                finalize = { precheck, actualBodySha256Hex -> authSupport.finalizeSignature(call, precheck, actualBodySha256Hex) },
            ) { rawBody ->
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
        ) {
            return@post
        }
    }

    post("/query/recent-threads") {
        val availableRepo = repo ?: internalServerFailure("member repository unavailable")
        if (
            !withVerifiedProtectedBody(
                call = call,
                maxBodyBytes = MAX_QUERY_REQUEST_BODY_BYTES,
                bodyReader = protectedBodyReader,
                precheck = { authSupport.precheckBotControlSignature(call, method = "POST") },
                finalize = { precheck, actualBodySha256Hex -> authSupport.finalizeSignature(call, precheck, actualBodySha256Hex) },
            ) { rawBody ->
                val request = rawBody.decodeJson(serverJson, QueryRecentThreadsRequest.serializer())
                call.respond(availableRepo.listThreads(request.chatId))
            }
        ) {
            return@post
        }
    }

    post("/query/recent-messages") {
        val availableRepo = repo ?: internalServerFailure("member repository unavailable")
        if (
            !withVerifiedProtectedBody(
                call = call,
                maxBodyBytes = MAX_QUERY_REQUEST_BODY_BYTES,
                bodyReader = protectedBodyReader,
                precheck = { authSupport.precheckBotControlSignature(call, method = "POST") },
                finalize = { precheck, actualBodySha256Hex -> authSupport.finalizeSignature(call, precheck, actualBodySha256Hex) },
            ) { rawBody ->
                val request = rawBody.decodeJson(serverJson, QueryRecentMessagesRequest.serializer())
                call.respond(availableRepo.listRecentMessages(request.chatId, request.limit))
            }
        ) {
            return@post
        }
    }
}
