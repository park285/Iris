package party.qwer.iris.http

import io.ktor.http.ContentType
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.withTimeoutOrNull
import party.qwer.iris.MemberRepository
import party.qwer.iris.SseEventBus
import party.qwer.iris.invalidRequest

private const val SSE_HEARTBEAT_INTERVAL_MS = 30_000L

internal fun Route.installMemberRoutes(
    authSupport: AuthSupport,
    memberRepo: MemberRepository?,
    sseEventBus: SseEventBus?,
) {
    val repo = memberRepo ?: return
    val bus = sseEventBus

    get("/rooms") {
        if (!authSupport.requireBotControlSignature(call, method = "GET")) return@get
        call.respond(repo.listRooms())
    }
    get("/rooms/{chatId}/members") {
        if (!authSupport.requireBotControlSignature(call, method = "GET")) return@get
        val chatId = call.parameters["chatId"]?.toLongOrNull() ?: invalidRequest("chatId must be a number")
        call.respond(repo.listMembers(chatId))
    }
    get("/rooms/{chatId}/info") {
        if (!authSupport.requireBotControlSignature(call, method = "GET")) return@get
        val chatId = call.parameters["chatId"]?.toLongOrNull() ?: invalidRequest("chatId must be a number")
        call.respond(repo.roomInfo(chatId))
    }
    get("/rooms/{chatId}/stats") {
        if (!authSupport.requireBotControlSignature(call, method = "GET")) return@get
        val chatId = call.parameters["chatId"]?.toLongOrNull() ?: invalidRequest("chatId must be a number")
        val period = call.request.queryParameters["period"]
        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
        val minMessages = call.request.queryParameters["minMessages"]?.toIntOrNull() ?: 0
        call.respond(repo.roomStats(chatId, period, limit, minMessages))
    }
    get("/rooms/{chatId}/members/{userId}/activity") {
        if (!authSupport.requireBotControlSignature(call, method = "GET")) return@get
        val chatId = call.parameters["chatId"]?.toLongOrNull() ?: invalidRequest("chatId must be a number")
        val userId = call.parameters["userId"]?.toLongOrNull() ?: invalidRequest("userId must be a number")
        val period = call.request.queryParameters["period"]
        call.respond(repo.memberActivity(chatId, userId, period))
    }
    get("/rooms/{chatId}/threads") {
        if (!authSupport.requireBotControlSignature(call, method = "GET")) return@get
        val chatId = call.parameters["chatId"]?.toLongOrNull() ?: invalidRequest("chatId must be a number")
        call.respond(repo.listThreads(chatId))
    }

    if (bus != null) {
        get("/events/stream") {
            if (!authSupport.requireBotControlSignature(call, method = "GET")) return@get
            val lastEventId = call.request.headers["Last-Event-ID"]?.toLongOrNull() ?: 0L
            call.respondBytesWriter(contentType = ContentType.Text.EventStream) {
                writeStringUtf8(initialSseFrames(bus.replayEnvelopesSuspend(lastEventId)))
                flush()
                val channel = bus.openSubscriberChannelSuspend()
                try {
                    while (true) {
                        val result = withTimeoutOrNull(SSE_HEARTBEAT_INTERVAL_MS) {
                            channel.receiveCatching()
                        }
                        when {
                            result == null -> {
                                writeStringUtf8(": keepalive\n\n")
                                flush()
                            }
                            result.isSuccess -> {
                                val envelope = result.getOrThrow()
                                writeStringUtf8("id: ${envelope.id}\nevent: ${envelope.eventType}\ndata: ${envelope.payload}\n\n")
                                flush()
                            }
                            else -> break
                        }
                    }
                } finally {
                    bus.removeSubscriberSuspend(channel)
                    channel.close()
                }
            }
        }
    }
}
