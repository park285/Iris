package party.qwer.iris.http

import io.ktor.http.ContentType
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.channels.Channel
import party.qwer.iris.MemberRepository
import party.qwer.iris.SseEventBus
import party.qwer.iris.invalidRequest

internal fun Route.installMemberRoutes(
    authSupport: AuthSupport,
    memberRepo: MemberRepository?,
    sseEventBus: SseEventBus?,
) {
    val repo = memberRepo ?: return
    val bus = sseEventBus

    get("/rooms") {
        if (!authSupport.requireBotToken(call, method = "GET")) return@get
        call.respond(repo.listRooms())
    }
    get("/rooms/{chatId}/members") {
        if (!authSupport.requireBotToken(call, method = "GET")) return@get
        val chatId = call.parameters["chatId"]?.toLongOrNull() ?: invalidRequest("chatId must be a number")
        call.respond(repo.listMembers(chatId))
    }
    get("/rooms/{chatId}/info") {
        if (!authSupport.requireBotToken(call, method = "GET")) return@get
        val chatId = call.parameters["chatId"]?.toLongOrNull() ?: invalidRequest("chatId must be a number")
        call.respond(repo.roomInfo(chatId))
    }
    get("/rooms/{chatId}/stats") {
        if (!authSupport.requireBotToken(call, method = "GET")) return@get
        val chatId = call.parameters["chatId"]?.toLongOrNull() ?: invalidRequest("chatId must be a number")
        val period = call.request.queryParameters["period"]
        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
        val minMessages = call.request.queryParameters["minMessages"]?.toIntOrNull() ?: 0
        call.respond(repo.roomStats(chatId, period, limit, minMessages))
    }
    get("/rooms/{chatId}/members/{userId}/activity") {
        if (!authSupport.requireBotToken(call, method = "GET")) return@get
        val chatId = call.parameters["chatId"]?.toLongOrNull() ?: invalidRequest("chatId must be a number")
        val userId = call.parameters["userId"]?.toLongOrNull() ?: invalidRequest("userId must be a number")
        val period = call.request.queryParameters["period"]
        call.respond(repo.memberActivity(chatId, userId, period))
    }
    get("/rooms/{chatId}/threads") {
        if (!authSupport.requireBotToken(call, method = "GET")) return@get
        val chatId = call.parameters["chatId"]?.toLongOrNull() ?: invalidRequest("chatId must be a number")
        call.respond(repo.listThreads(chatId))
    }

    if (bus != null) {
        get("/events/stream") {
            if (!authSupport.requireBotToken(call, method = "GET")) return@get
            val lastEventId = call.request.headers["Last-Event-ID"]?.toLongOrNull() ?: 0L
            call.respondBytesWriter(contentType = ContentType.Text.EventStream) {
                writeStringUtf8(initialSseFrames(bus.replayEnvelopes(lastEventId)))
                flush()
                val channel = Channel<SseEventEnvelope>(64)
                bus.addSubscriber(channel)
                try {
                    for (envelope in channel) {
                        writeStringUtf8("id: ${envelope.id}\ndata: ${envelope.payload}\n\n")
                        flush()
                    }
                } finally {
                    bus.removeSubscriber(channel)
                    channel.close()
                }
            }
        }
    }
}
