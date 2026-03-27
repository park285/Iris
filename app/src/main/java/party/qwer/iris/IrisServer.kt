package party.qwer.iris

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.application.serverConfig
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.utils.io.writeStringUtf8
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import party.qwer.iris.model.CommonErrorResponse
import party.qwer.iris.model.ConfigRequest
import party.qwer.iris.model.ImageBridgeHealthResult
import party.qwer.iris.model.QueryRequest
import party.qwer.iris.model.QueryResponse
import party.qwer.iris.model.ReplyAcceptedResponse
import party.qwer.iris.model.ReplyRequest
import party.qwer.iris.model.ReplyStatusSnapshot
import party.qwer.iris.model.ReplyType
import java.security.MessageDigest
import java.util.UUID

internal const val NETTY_NO_NATIVE_PROPERTY = "io.netty.transport.noNative"

internal fun enforceNettyNioTransport(): Boolean {
    if (System.getProperty(NETTY_NO_NATIVE_PROPERTY) == "true") {
        return false
    }
    System.setProperty(NETTY_NO_NATIVE_PROPERTY, "true")
    return true
}

internal class IrisServer(
    private val chatLogRepo: ChatLogRepository,
    private val configManager: ConfigManager,
    private val notificationReferer: String,
    private val messageSender: MessageSender,
    private val bridgeHealthProvider: (() -> ImageBridgeHealthResult)? = null,
    private val replyStatusProvider: ((String) -> ReplyStatusSnapshot?)? = null,
    private val memberRepo: MemberRepository? = null,
    private val sseEventBus: SseEventBus? = null,
    private val chatRoomIntrospectProvider: ((Long) -> String?)? = null,
) {
    private val serverJson =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    @Volatile
    private var server: EmbeddedServer<*, *>? = null

    @Synchronized
    fun startServer() {
        if (server != null) {
            IrisLogger.debug("[IrisServer] startServer called while server is already running")
            return
        }

        if (enforceNettyNioTransport()) {
            IrisLogger.info("[IrisServer] forcing Netty JVM NIO transport (io.netty.transport.noNative=true)")
        }

        server = createServer().also { it.start(wait = false) }
    }

    @Synchronized
    fun stopServer() {
        val runningServer = server ?: return
        runCatching {
            runningServer.stop(SERVER_GRACE_PERIOD_MS, SERVER_STOP_TIMEOUT_MS)
            IrisLogger.info("[IrisServer] HTTP server stopped")
        }.onFailure { error ->
            IrisLogger.error("[IrisServer] Failed to stop HTTP server: ${error.message}", error)
        }
        server = null
    }

    private fun createServer(): EmbeddedServer<*, *> {
        val environment = applicationEnvironment { }
        val config =
            serverConfig(environment) {
                module {
                    with(this@IrisServer) {
                        configureContentNegotiation()
                        configureStatusPages()
                        configureRouting()
                    }
                }
            }
        return embeddedServer(Netty, config) {
            connector {
                port = configManager.botSocketPort
                host = "0.0.0.0"
            }
            enableHttp2 = true
            enableH2c = true
            workerGroupSize = NETTY_WORKER_THREADS
        }
    }

    private fun Application.configureContentNegotiation() {
        install(ContentNegotiation) {
            json(serverJson)
        }
    }

    private fun Application.configureStatusPages() {
        install(StatusPages) {
            exception<ApiRequestException> { call, cause ->
                call.respond(
                    cause.status,
                    CommonErrorResponse(message = cause.message),
                )
            }

            exception<SerializationException> { call, cause ->
                IrisLogger.error("[IrisServer] Invalid JSON request: ${cause.message}")
                call.respond(
                    HttpStatusCode.BadRequest,
                    CommonErrorResponse(message = "invalid json request"),
                )
            }

            exception<Throwable> { call, cause ->
                IrisLogger.error("[IrisServer] Unhandled error: ${cause.message}", cause)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    CommonErrorResponse(message = "internal server error"),
                )
            }
        }
    }

    private fun Application.configureRouting() {
        routing {
            configureHealthRoutes()
            configureConfigRoutes()
            configureReplyRoute()
            configureReplyImageRoute()
            configureReplyMarkdownRoute()
            configureReplyStatusRoute()
            configureQueryRoute()
            configureMemberRoutes()
        }
    }

    private fun Route.configureHealthRoutes() {
        get("/health") {
            call.respondText(HEALTH_OK_JSON, ContentType.Application.Json)
        }

        get("/ready") {
            val bridgeHealth = bridgeHealthProvider?.invoke()
            if (bridgeHealth == null || isBridgeReady(bridgeHealth)) {
                call.respondText(READY_OK_JSON, ContentType.Application.Json)
            } else {
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    CommonErrorResponse(message = "bridge not ready"),
                )
            }
        }

        get("/diagnostics/bridge") {
            if (!requireBotToken(call)) {
                return@get
            }
            val bridgeHealth = bridgeHealthProvider?.invoke() ?: invalidRequest("bridge health unavailable")
            call.respond(bridgeHealth)
        }

        get("/diagnostics/chatroom-fields/{chatId}") {
            if (!requireBotToken(call)) return@get
            val chatId = call.parameters["chatId"]?.toLongOrNull()
                ?: invalidRequest("chatId must be a number")
            val result = chatRoomIntrospectProvider?.invoke(chatId)
                ?: invalidRequest("bridge introspection unavailable")
            call.respondText(result, ContentType.Application.Json)
        }
    }

    private fun Route.configureConfigRoutes() {
        route("/config") {
            get {
                if (!requireBotToken(call)) {
                    return@get
                }
                call.respond(configManager.configResponse())
            }

            post("{name}") {
                if (!requireBotToken(call)) {
                    return@post
                }

                val name = call.parameters["name"] ?: throw ApiRequestException("missing config name")
                val request = call.receive<ConfigRequest>()
                val updateOutcome = applyConfigUpdate(configManager, name, request)

                if (!configManager.saveConfigNow()) {
                    throw ApiRequestException(
                        "failed to persist config update",
                        HttpStatusCode.InternalServerError,
                    )
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

    private fun Route.configureReplyRoute() {
        post("/reply") {
            if (!requireBotToken(call)) {
                return@post
            }

            val replyRequest = call.receive<ReplyRequest>()
            val response = enqueueReply(replyRequest)
            call.respond(HttpStatusCode.Accepted, response)
        }
    }

    private fun Route.configureReplyMarkdownRoute() {
        post("/reply-markdown") {
            if (!requireBotToken(call)) {
                return@post
            }

            val replyRequest = call.receive<ReplyRequest>()
            val response = enqueueReplyMarkdown(replyRequest)
            call.respond(HttpStatusCode.Accepted, response)
        }
    }

    private fun Route.configureReplyStatusRoute() {
        get("/reply-status/{requestId}") {
            if (!requireBotToken(call)) {
                return@get
            }
            val requestId = call.parameters["requestId"] ?: invalidRequest("missing requestId")
            val snapshot = replyStatusProvider?.invoke(requestId) ?: throw ApiRequestException("reply status not found", HttpStatusCode.NotFound)
            call.respond(snapshot)
        }
    }

    private fun Route.configureReplyImageRoute() {
        post("/reply-image") {
            if (!requireBotToken(call)) {
                return@post
            }

            val replyRequest = call.receive<ReplyRequest>()
            val response = enqueueReplyImage(replyRequest)
            call.respond(HttpStatusCode.Accepted, response)
        }
    }

    private fun Route.configureQueryRoute() {
        post("/query") {
            if (!requireBotToken(call)) {
                return@post
            }
            val queryRequest = call.receive<QueryRequest>()
            requireQueryText(queryRequest.query)
            requireReadOnlyQuery(queryRequest.query)

            try {
                call.respond(executeQueryRequest(queryRequest))
            } catch (e: ApiRequestException) {
                throw e
            } catch (e: Exception) {
                IrisLogger.error("[IrisServer] Query execution failed", e)
                internalServerFailure("query execution failed")
            }
        }
    }

    private fun enqueueReply(replyRequest: ReplyRequest): ReplyAcceptedResponse {
        val requestId = "reply-${UUID.randomUUID()}"
        val roomId = replyRequest.room.toLongOrNull() ?: invalidRequest("room must be a numeric string")
        val threadId =
            replyRequest.threadId?.let {
                it.toLongOrNull() ?: invalidRequest("threadId must be a numeric string")
            }
        val threadScope =
            try {
                validateReplyThreadScope(replyRequest.type, threadId, replyRequest.threadScope)
            } catch (e: IllegalArgumentException) {
                invalidRequest(e.message ?: "invalid threadScope")
            }
        if (threadId != null && !supportsThreadReply(replyRequest.type)) {
            invalidRequest("threadId is only supported for text replies")
        }

        val admission =
            admitReply(
                replyRequest,
                roomId,
                notificationReferer,
                threadId,
                threadScope,
                messageSender,
                requestId,
            )
        if (admission.status != ReplyAdmissionStatus.ACCEPTED) {
            requestRejected(
                admission.message ?: "reply request rejected",
                replyAdmissionHttpStatus(admission.status),
            )
        }

        return ReplyAcceptedResponse(
            requestId = requestId,
            room = replyRequest.room,
            type = replyRequest.type,
        )
    }

    private fun enqueueReplyMarkdown(replyRequest: ReplyRequest): ReplyAcceptedResponse {
        val requestId = "reply-markdown-${UUID.randomUUID()}"
        validateReplyMarkdownType(replyRequest.type)

        val roomId = replyRequest.room.toLongOrNull() ?: invalidRequest("room must be a numeric string")
        val threadId =
            replyRequest.threadId?.let {
                it.toLongOrNull() ?: invalidRequest("threadId must be a numeric string")
            }
        val threadScope = replyRequest.threadScope

        try {
            validateReplyMarkdownThreadMetadata(threadId, threadScope)
        } catch (e: IllegalArgumentException) {
            invalidRequest(e.message ?: "invalid reply-markdown metadata")
        }

        val admission = messageSender.sendReplyMarkdown(roomId, extractTextPayload(replyRequest), requestId)
        if (admission.status != ReplyAdmissionStatus.ACCEPTED) {
            requestRejected(
                admission.message ?: "reply-markdown request rejected",
                replyAdmissionHttpStatus(admission.status),
            )
        }

        return ReplyAcceptedResponse(
            requestId = requestId,
            room = replyRequest.room,
            type = replyRequest.type,
        )
    }

    private fun enqueueReplyImage(replyRequest: ReplyRequest): ReplyAcceptedResponse {
        val requestId = "reply-image-${UUID.randomUUID()}"
        validateReplyImageType(replyRequest.type)

        val roomId = replyRequest.room.toLongOrNull() ?: invalidRequest("room must be a numeric string")
        val threadId =
            replyRequest.threadId?.let {
                it.toLongOrNull() ?: invalidRequest("threadId must be a numeric string")
            }
        val threadScope =
            try {
                validateReplyImageThreadScope(threadId, replyRequest.threadScope)
            } catch (e: IllegalArgumentException) {
                invalidRequest(e.message ?: "invalid reply-image thread metadata")
            }

        val admission =
            when (replyRequest.type) {
                ReplyType.IMAGE ->
                    messageSender.sendNativePhoto(
                        roomId,
                        extractTextPayload(replyRequest),
                        threadId,
                        threadScope,
                        requestId,
                    )
                ReplyType.IMAGE_MULTIPLE ->
                    messageSender.sendNativeMultiplePhotos(
                        roomId,
                        extractImagePayloads(replyRequest),
                        threadId,
                        threadScope,
                        requestId,
                    )
                else -> invalidRequest("reply-image replies require type=image or image_multiple")
            }
        if (admission.status != ReplyAdmissionStatus.ACCEPTED) {
            requestRejected(
                admission.message ?: "reply-image request rejected",
                replyAdmissionHttpStatus(admission.status),
            )
        }

        return ReplyAcceptedResponse(
            requestId = requestId,
            room = replyRequest.room,
            type = replyRequest.type,
        )
    }

    private fun Route.configureMemberRoutes() {
        val repo = memberRepo ?: return
        val bus = sseEventBus

        get("/rooms") {
            if (!requireBotToken(call)) return@get
            call.respond(repo.listRooms())
        }

        get("/rooms/{chatId}/members") {
            if (!requireBotToken(call)) return@get
            val chatId = call.parameters["chatId"]?.toLongOrNull()
                ?: invalidRequest("chatId must be a number")
            call.respond(repo.listMembers(chatId))
        }

        get("/rooms/{chatId}/info") {
            if (!requireBotToken(call)) return@get
            val chatId = call.parameters["chatId"]?.toLongOrNull()
                ?: invalidRequest("chatId must be a number")
            call.respond(repo.roomInfo(chatId))
        }

        get("/rooms/{chatId}/stats") {
            if (!requireBotToken(call)) return@get
            val chatId = call.parameters["chatId"]?.toLongOrNull()
                ?: invalidRequest("chatId must be a number")
            val period = call.request.queryParameters["period"]
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
            call.respond(repo.roomStats(chatId, period, limit))
        }

        get("/rooms/{chatId}/members/{userId}/activity") {
            if (!requireBotToken(call)) return@get
            val chatId = call.parameters["chatId"]?.toLongOrNull()
                ?: invalidRequest("chatId must be a number")
            val userId = call.parameters["userId"]?.toLongOrNull()
                ?: invalidRequest("userId must be a number")
            val period = call.request.queryParameters["period"]
            call.respond(repo.memberActivity(chatId, userId, period))
        }

        if (bus != null) {
            get("/events/stream") {
                if (!requireBotToken(call)) return@get
                val lastEventId = call.request.headers["Last-Event-ID"]?.toLongOrNull() ?: 0L
                call.respondBytesWriter(contentType = ContentType.Text.EventStream) {
                    // Replay buffered events
                    for ((id, data) in bus.replayFrom(lastEventId)) {
                        writeStringUtf8("id: $id\ndata: $data\n\n")
                        flush()
                    }
                    // Subscribe to live events
                    val channel = kotlinx.coroutines.channels.Channel<Pair<Long, String>>(64)
                    val listener: (Long, String) -> Unit = { id, data -> channel.trySend(id to data) }
                    bus.listeners.add(listener)
                    try {
                        for ((id, data) in channel) {
                            writeStringUtf8("id: $id\ndata: $data\n\n")
                            flush()
                        }
                    } finally {
                        bus.listeners.remove(listener)
                        channel.close()
                    }
                }
            }
        }
    }

    private suspend fun requireBotToken(call: ApplicationCall): Boolean {
        val expectedToken = configManager.botToken
        if (expectedToken.isBlank()) {
            IrisLogger.error("[IrisServer] Refusing protected request because bot token is not configured")
            call.respond(HttpStatusCode.ServiceUnavailable, CommonErrorResponse(message = "service unavailable"))
            return false
        }
        val provided = call.request.headers[HEADER_BOT_TOKEN].orEmpty()
        if (
            MessageDigest.isEqual(
                provided.toByteArray(Charsets.UTF_8),
                expectedToken.toByteArray(Charsets.UTF_8),
            )
        ) {
            return true
        }

        call.respond(HttpStatusCode.Unauthorized, CommonErrorResponse(message = "unauthorized"))
        return false
    }

    companion object {
        private const val MAX_QUERY_ROWS = 500
        private const val HEADER_BOT_TOKEN = "X-Bot-Token"
        private const val SERVER_GRACE_PERIOD_MS = 1_000L
        private const val SERVER_STOP_TIMEOUT_MS = 5_000L
        private const val NETTY_WORKER_THREADS = 2
        private const val HEALTH_OK_JSON = """{"status":"ok"}"""
        private const val READY_OK_JSON = """{"status":"ready"}"""
        private val REQUIRED_DISCOVERY_HOOKS = setOf("ChatMediaSender#sendSingle", "ChatMediaSender#sendMultiple")

        internal fun isBridgeReadyForTest(health: ImageBridgeHealthResult): Boolean = isBridgeReady(health)

        private fun isBridgeReady(health: ImageBridgeHealthResult): Boolean {
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
    }

    private fun executeQueryRequest(queryRequest: QueryRequest): QueryResponse {
        val rows =
            chatLogRepo.executeQuery(
                queryRequest.query,
                (queryRequest.bind?.map { it.content } ?: listOf()).toTypedArray(),
                MAX_QUERY_ROWS + 1,
            )
        if (rows.size > MAX_QUERY_ROWS) {
            invalidRequest("query returned too many rows; limit is $MAX_QUERY_ROWS")
        }

        return QueryResponse(
            rowCount = rows.size,
            data =
                rows.map {
                    decryptRow(it, configManager)
                },
        )
    }
}

internal class ApiRequestException(
    override val message: String,
    val status: HttpStatusCode = HttpStatusCode.BadRequest,
) : RuntimeException(message)

internal fun validateReplyThreadScope(
    replyType: ReplyType,
    threadId: Long?,
    threadScope: Int?,
): Int? {
    val normalizedScope =
        threadScope?.takeIf { it > 0 } ?: threadScope?.let {
            throw IllegalArgumentException("threadScope must be a positive integer")
        }
            ?: return null

    require(supportsThreadReply(replyType)) { "threadId is only supported for text replies" }
    require(threadId != null || normalizedScope == 1) { "threadScope requires threadId unless scope is 1" }
    return normalizedScope
}

internal fun validateReplyImageType(replyType: ReplyType) {
    require(replyType == ReplyType.IMAGE || replyType == ReplyType.IMAGE_MULTIPLE) {
        "reply-image replies require type=image or image_multiple"
    }
}

internal fun validateReplyImageThreadScope(
    threadId: Long?,
    threadScope: Int?,
): Int? {
    if (threadId == null && threadScope == null) {
        return null
    }
    require(threadId != null) { "reply-image threadScope requires threadId" }
    val normalizedScope = threadScope ?: throw IllegalArgumentException("reply-image threadId requires threadScope")
    require(normalizedScope == 2 || normalizedScope == 3) { "reply-image threadScope must be 2 or 3" }
    return normalizedScope
}

private fun invalidRequest(message: String): Nothing = throw ApiRequestException(message)

private fun internalServerFailure(message: String): Nothing = throw ApiRequestException(message, HttpStatusCode.InternalServerError)

private fun requestRejected(
    message: String,
    status: HttpStatusCode,
): Nothing = throw ApiRequestException(message, status)

private fun requireQueryText(query: String) {
    if (query.isBlank()) {
        invalidRequest("query must not be blank")
    }
}

private fun requireReadOnlyQuery(query: String) {
    if (!isReadOnlyQuery(query)) {
        invalidRequest("only SELECT, WITH...SELECT, and safe PRAGMA queries are allowed")
    }
}
