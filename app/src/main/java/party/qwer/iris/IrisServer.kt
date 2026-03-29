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
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.utils.io.writeStringUtf8
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import party.qwer.iris.model.ApiErrorResponse
import party.qwer.iris.model.CommonErrorResponse
import party.qwer.iris.model.ConfigRequest
import party.qwer.iris.model.ImageBridgeHealthResult
import party.qwer.iris.model.QueryRequest
import party.qwer.iris.model.QueryResponse
import party.qwer.iris.model.ReplyAcceptedResponse
import party.qwer.iris.model.ReplyRequest
import party.qwer.iris.model.ReplyStatusSnapshot
import party.qwer.iris.model.ReplyType
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
    private val bridgeHealthProvider: (() -> ImageBridgeHealthResult?)? = null,
    private val replyStatusProvider: ((String) -> ReplyStatusSnapshot?)? = null,
    private val memberRepo: MemberRepository? = null,
    private val sseEventBus: SseEventBus? = null,
    private val chatRoomIntrospectProvider: ((Long) -> String?)? = null,
    private val bindHost: String = DEFAULT_BIND_HOST,
    private val nettyWorkerThreads: Int = DEFAULT_NETTY_WORKER_THREADS,
) {
    private val requestAuthenticator = RequestAuthenticator()
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
                host = bindHost
            }
            enableHttp2 = runtimeHttp2Enabled()
            enableH2c = runtimeH2cEnabled()
            workerGroupSize = nettyWorkerThreads
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
                val path = call.request.path()
                if (path.startsWith("/rooms") || path.startsWith("/events") || path.startsWith("/diagnostics/chatroom")) {
                    call.respond(
                        cause.status,
                        ApiErrorResponse(
                            error = cause.message,
                            code = mapApiErrorCode(cause.status),
                        ),
                    )
                } else {
                    call.respond(
                        cause.status,
                        CommonErrorResponse(message = cause.message),
                    )
                }
            }

            exception<SerializationException> { call, cause ->
                IrisLogger.error("[IrisServer] Invalid JSON request: ${cause.message}")
                val path = call.request.path()
                if (path.startsWith("/rooms") || path.startsWith("/events") || path.startsWith("/diagnostics/chatroom")) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ApiErrorResponse(error = "invalid json request", code = "INVALID_REQUEST"),
                    )
                } else {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        CommonErrorResponse(message = "invalid json request"),
                    )
                }
            }

            exception<Throwable> { call, cause ->
                IrisLogger.error("[IrisServer] Unhandled error: ${cause.message}", cause)
                val path = call.request.path()
                if (path.startsWith("/rooms") || path.startsWith("/events") || path.startsWith("/diagnostics/chatroom")) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        ApiErrorResponse(error = "internal server error", code = "INTERNAL_ERROR"),
                    )
                } else {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        CommonErrorResponse(message = "internal server error"),
                    )
                }
            }
        }
    }

    private fun Application.configureRouting() {
        routing {
            configureHealthRoutes()
            configureConfigRoutes()
            configureReplyRoute()
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
            if (!requireBotToken(call, method = "GET")) {
                return@get
            }
            val bridgeHealth = bridgeHealthProvider?.invoke() ?: invalidRequest("bridge health unavailable")
            call.respond(bridgeHealth)
        }

        get("/diagnostics/chatroom-fields/{chatId}") {
            if (!requireBotToken(call, method = "GET")) return@get
            val chatId =
                call.parameters["chatId"]?.toLongOrNull()
                    ?: invalidRequest("chatId must be a number")
            val result =
                chatRoomIntrospectProvider?.invoke(chatId)
                    ?: invalidRequest("bridge introspection unavailable")
            call.respondText(result, ContentType.Application.Json)
        }
    }

    private fun Route.configureConfigRoutes() {
        route("/config") {
            get {
                if (!requireBotToken(call, method = "GET")) {
                    return@get
                }
                call.respond(configManager.configResponse())
            }

            post("{name}") {
                handleConfigUpdate(call)
            }
        }
    }

    private fun Route.configureReplyRoute() {
        post("/reply") {
            val rawBody = readProtectedRequestBody(call, MAX_REPLY_REQUEST_BODY_BYTES)
            if (!requireBotToken(call, method = "POST", body = rawBody.body, bodySha256Hex = rawBody.sha256Hex)) {
                return@post
            }

            val replyRequest = serverJson.decodeFromString<ReplyRequest>(rawBody.body)
            val response = enqueueReply(replyRequest)
            call.respond(HttpStatusCode.Accepted, response)
        }
    }

    private fun Route.configureReplyStatusRoute() {
        get("/reply-status/{requestId}") {
            if (!requireBotToken(call, method = "GET")) {
                return@get
            }
            val requestId = call.parameters["requestId"] ?: invalidRequest("missing requestId")
            val snapshot = replyStatusProvider?.invoke(requestId) ?: throw ApiRequestException("reply status not found", HttpStatusCode.NotFound)
            call.respond(snapshot)
        }
    }

    private fun Route.configureQueryRoute() {
        post("/query") {
            val rawBody = readProtectedRequestBody(call, MAX_QUERY_REQUEST_BODY_BYTES)
            if (!requireBotToken(call, method = "POST", body = rawBody.body, bodySha256Hex = rawBody.sha256Hex)) {
                return@post
            }
            val queryRequest = serverJson.decodeFromString<QueryRequest>(rawBody.body)
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
            if (replyRequest.type == ReplyType.MARKDOWN) {
                try {
                    validateReplyMarkdownThreadMetadata(threadId, replyRequest.threadScope)
                } catch (e: IllegalArgumentException) {
                    invalidRequest(e.message ?: "invalid thread metadata")
                }
            } else {
                try {
                    validateReplyThreadScope(replyRequest.type, threadId, replyRequest.threadScope)
                } catch (e: IllegalArgumentException) {
                    invalidRequest(e.message ?: "invalid threadScope")
                }
            }
        if (threadId != null && !supportsThreadReply(replyRequest.type)) {
            invalidRequest("threadId is not supported for this reply type")
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

    private fun Route.configureMemberRoutes() {
        val repo = memberRepo ?: return
        val bus = sseEventBus

        get("/rooms") {
            if (!requireBotToken(call, method = "GET")) return@get
            call.respond(repo.listRooms())
        }

        get("/rooms/{chatId}/members") {
            if (!requireBotToken(call, method = "GET")) return@get
            val chatId =
                call.parameters["chatId"]?.toLongOrNull()
                    ?: invalidRequest("chatId must be a number")
            call.respond(repo.listMembers(chatId))
        }

        get("/rooms/{chatId}/info") {
            if (!requireBotToken(call, method = "GET")) return@get
            val chatId =
                call.parameters["chatId"]?.toLongOrNull()
                    ?: invalidRequest("chatId must be a number")
            call.respond(repo.roomInfo(chatId))
        }

        get("/rooms/{chatId}/stats") {
            if (!requireBotToken(call, method = "GET")) return@get
            val chatId =
                call.parameters["chatId"]?.toLongOrNull()
                    ?: invalidRequest("chatId must be a number")
            val period = call.request.queryParameters["period"]
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
            val minMessages = call.request.queryParameters["minMessages"]?.toIntOrNull() ?: 0
            call.respond(repo.roomStats(chatId, period, limit, minMessages))
        }

        get("/rooms/{chatId}/members/{userId}/activity") {
            if (!requireBotToken(call, method = "GET")) return@get
            val chatId =
                call.parameters["chatId"]?.toLongOrNull()
                    ?: invalidRequest("chatId must be a number")
            val userId =
                call.parameters["userId"]?.toLongOrNull()
                    ?: invalidRequest("userId must be a number")
            val period = call.request.queryParameters["period"]
            call.respond(repo.memberActivity(chatId, userId, period))
        }

        if (bus != null) {
            get("/events/stream") {
                if (!requireBotToken(call, method = "GET")) return@get
                val lastEventId = call.request.headers["Last-Event-ID"]?.toLongOrNull() ?: 0L
                call.respondBytesWriter(contentType = ContentType.Text.EventStream) {
                    writeStringUtf8(initialSseFrames(bus.replayFrom(lastEventId)))
                    flush()
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

    private suspend fun requireBotToken(
        call: ApplicationCall,
        method: String,
        body: String = "",
        bodySha256Hex: String = sha256Hex(body.toByteArray()),
    ): Boolean {
        val expectedToken = configManager.inboundSigningSecret
        when (
            requestAuthenticator.authenticate(
                method = method,
                path = canonicalRequestTarget(call.request.path(), call.request.queryParameters),
                body = body,
                bodySha256Hex = bodySha256Hex,
                expectedSecret = expectedToken,
                timestampHeader = call.request.headers[HEADER_IRIS_TIMESTAMP],
                nonceHeader = call.request.headers[HEADER_IRIS_NONCE],
                signatureHeader = call.request.headers[HEADER_IRIS_SIGNATURE],
            )
        ) {
            AuthResult.AUTHORIZED -> return true
            AuthResult.SERVICE_UNAVAILABLE -> {
                IrisLogger.error("[IrisServer] Refusing protected request because bot token is not configured")
                call.respond(HttpStatusCode.ServiceUnavailable, CommonErrorResponse(message = "service unavailable"))
                return false
            }
            AuthResult.UNAUTHORIZED -> {
                call.respond(HttpStatusCode.Unauthorized, CommonErrorResponse(message = "unauthorized"))
                return false
            }
        }
    }

    companion object {
        private const val MAX_QUERY_ROWS = 500
        private const val MAX_CONFIG_REQUEST_BODY_BYTES = 64 * 1024
        private const val MAX_QUERY_REQUEST_BODY_BYTES = 256 * 1024
        private const val MAX_REPLY_REQUEST_BODY_BYTES = 48 * 1024 * 1024
        private const val HEADER_IRIS_TIMESTAMP = "X-Iris-Timestamp"
        private const val HEADER_IRIS_NONCE = "X-Iris-Nonce"
        private const val HEADER_IRIS_SIGNATURE = "X-Iris-Signature"
        private const val SERVER_GRACE_PERIOD_MS = 1_000L
        private const val SERVER_STOP_TIMEOUT_MS = 5_000L
        private const val DEFAULT_BIND_HOST = "127.0.0.1"
        private const val DEFAULT_NETTY_WORKER_THREADS = 2
        private const val DEFAULT_ENABLE_HTTP2 = false
        private const val DEFAULT_ENABLE_H2C = false
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

        internal fun isBridgeReadyForTest(health: ImageBridgeHealthResult): Boolean = isBridgeReady(health)

        internal fun runtimeHttp2Enabled(): Boolean = DEFAULT_ENABLE_HTTP2

        internal fun runtimeH2cEnabled(): Boolean = DEFAULT_ENABLE_H2C

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

        private fun initialSseFrames(replay: List<Pair<Long, String>>): String =
            buildString {
                append(": connected\n\n")
                for ((id, data) in replay) {
                    append("id: ")
                    append(id)
                    append("\ndata: ")
                    append(data)
                    append("\n\n")
                }
            }

        internal fun initialSseFramesForTest(replay: List<Pair<Long, String>>): String = initialSseFrames(replay)
    }

    private fun executeQueryRequest(queryRequest: QueryRequest): QueryResponse {
        val queryResult =
            chatLogRepo.executeQuery(
                queryRequest.query,
                (queryRequest.bind?.map { it.content } ?: listOf()).toTypedArray(),
                MAX_QUERY_ROWS + 1,
            )
        if (queryResult.rows.size > MAX_QUERY_ROWS) {
            invalidRequest("query returned too many rows; limit is $MAX_QUERY_ROWS")
        }

        return buildQueryResponse(
            queryResult = queryResult,
            decrypt = queryRequest.decrypt,
            config = configManager,
        )
    }

    private suspend fun handleConfigUpdate(call: ApplicationCall) {
        val bodyResult = readProtectedRequestBody(call, MAX_CONFIG_REQUEST_BODY_BYTES)
        if (!requireBotToken(call, method = "POST", body = bodyResult.body, bodySha256Hex = bodyResult.sha256Hex)) {
            return
        }

        val name = call.parameters["name"] ?: throw ApiRequestException("missing config name")
        val request = serverJson.decodeFromString<ConfigRequest>(bodyResult.body)
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

    require(supportsThreadReply(replyType)) { "threadScope is not supported for this reply type" }
    require(threadId != null || normalizedScope == 1) { "threadScope requires threadId unless scope is 1" }
    return normalizedScope
}
