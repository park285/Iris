package party.qwer.iris

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.encodeURLParameter
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
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeStringUtf8
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import party.qwer.iris.model.ApiErrorResponse
import party.qwer.iris.model.CommonErrorResponse
import party.qwer.iris.model.ConfigRequest
import party.qwer.iris.model.ImageBridgeHealthResult
import party.qwer.iris.model.QueryColumn
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

    private fun Route.configureReplyMarkdownRoute() {
        post("/reply-markdown") {
            val rawBody = readProtectedRequestBody(call, MAX_REPLY_REQUEST_BODY_BYTES)
            if (!requireBotToken(call, method = "POST", body = rawBody.body, bodySha256Hex = rawBody.sha256Hex)) {
                return@post
            }

            val replyRequest = serverJson.decodeFromString<ReplyRequest>(rawBody.body)
            val response = enqueueReplyMarkdown(replyRequest)
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

    private fun Route.configureReplyImageRoute() {
        post("/reply-image") {
            val rawBody = readProtectedRequestBody(call, MAX_REPLY_REQUEST_BODY_BYTES)
            if (!requireBotToken(call, method = "POST", body = rawBody.body, bodySha256Hex = rawBody.sha256Hex)) {
                return@post
            }

            val replyRequest = serverJson.decodeFromString<ReplyRequest>(rawBody.body)
            val response = enqueueReplyImage(replyRequest)
            call.respond(HttpStatusCode.Accepted, response)
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
        val threadScope =
            try {
                validateReplyMarkdownThreadMetadata(threadId, replyRequest.threadScope)
            } catch (e: IllegalArgumentException) {
                invalidRequest(e.message ?: "invalid reply-markdown metadata")
            }

        val admission = messageSender.sendReplyMarkdown(roomId, extractTextPayload(replyRequest), threadId, threadScope, requestId)
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
        val expectedToken = configManager.signingSecret()
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

        private fun mapApiErrorCode(status: HttpStatusCode): String =
            when (status) {
                HttpStatusCode.BadRequest -> "INVALID_REQUEST"
                HttpStatusCode.NotFound -> "NOT_FOUND"
                HttpStatusCode.Unauthorized -> "UNAUTHORIZED"
                HttpStatusCode.Forbidden -> "FORBIDDEN"
                else -> "INTERNAL_ERROR"
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

private suspend fun readProtectedRequestBody(
    call: ApplicationCall,
    maxBodyBytes: Int,
): RequestBodyReadResult =
    readRequestBodyWithinLimit(
        bodyChannel = call.receiveChannel(),
        declaredContentLength = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull(),
        maxBodyBytes = maxBodyBytes,
    )

private fun canonicalRequestTarget(
    path: String,
    queryParameters: Parameters,
): String {
    val encodedQuery =
        queryParameters
            .entries()
            .asSequence()
            .flatMap { (name, values) ->
                if (values.isEmpty()) {
                    sequenceOf(name.encodeURLParameter() to null)
                } else {
                    values.asSequence().map { value ->
                        name.encodeURLParameter() to value.encodeURLParameter()
                    }
                }
            }.sortedWith(compareBy({ it.first }, { it.second.orEmpty() }))
            .joinToString("&") { (name, value) ->
                if (value == null) {
                    name
                } else {
                    "$name=$value"
                }
            }
    return if (encodedQuery.isBlank()) path else "$path?$encodedQuery"
}

internal suspend fun readRequestBodyWithinLimit(
    bodyChannel: ByteReadChannel,
    declaredContentLength: Long?,
    maxBodyBytes: Int,
): RequestBodyReadResult {
    if (declaredContentLength != null && declaredContentLength > maxBodyBytes) {
        requestRejected("request body too large", HttpStatusCode.PayloadTooLarge)
    }

    val buffer = ByteArray(DEFAULT_BODY_READ_BUFFER_BYTES)
    val output = java.io.ByteArrayOutputStream(minOf(maxBodyBytes, DEFAULT_BODY_READ_BUFFER_BYTES))
    var totalRead = 0
    while (true) {
        val read = bodyChannel.readAvailable(buffer, 0, buffer.size)
        if (read == -1) {
            break
        }
        totalRead += read
        if (totalRead > maxBodyBytes) {
            requestRejected("request body too large", HttpStatusCode.PayloadTooLarge)
        }
        output.write(buffer, 0, read)
    }
    val bytes = output.toByteArray()
    return RequestBodyReadResult(
        body = bytes.toString(Charsets.UTF_8),
        sha256Hex = sha256Hex(bytes),
    )
}

private const val DEFAULT_BODY_READ_BUFFER_BYTES = 8 * 1024

internal data class RequestBodyReadResult(
    val body: String,
    val sha256Hex: String,
)

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
    val replyImageScope = threadScope ?: throw IllegalArgumentException("reply-image threadId requires threadScope")
    require(replyImageScope == 2 || replyImageScope == 3) { "reply-image threadScope must be 2 or 3" }
    // threadScope는 Kakao 쪽 reply 표시 범위를 고르는 힌트다.
    // - 1: 채팅방 전체
    // - 2+: thread detail
    // image reply 실측 기준:
    // - 2: thread-only
    // - 3: thread + room 같이 보내기
    // 이 라우트는 caller가 의도한 범위를 그대로 bridge로 넘긴다.
    // thread-only 기본 동작이 필요하면 caller가 2를 명시해야 한다.
    return replyImageScope
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

internal fun buildQueryResponse(
    queryResult: QueryExecutionResult,
    decrypt: Boolean,
    config: ConfigProvider,
    decryptor: (Map<String, String?>, ConfigProvider) -> Map<String, String?> = ::decryptRow,
): QueryResponse {
    val legacyRows = toLegacyQueryRows(queryResult)
    val effectiveLegacyRows =
        if (decrypt) {
            legacyRows.map { row -> decryptor(row, config) }
        } else {
            legacyRows
        }
    val effectiveRows =
        queryResult.rows.indices.map { rowIndex ->
            applyLegacyRowOverlay(
                columns = queryResult.columns,
                originalRow = queryResult.rows[rowIndex],
                effectiveLegacyRow = effectiveLegacyRows[rowIndex],
            )
        }

    return QueryResponse(
        rowCount = effectiveRows.size,
        columns = queryResult.columns,
        rows = effectiveRows,
    )
}

internal fun toLegacyQueryRows(queryResult: QueryExecutionResult): List<Map<String, String?>> =
    queryResult.rows.map { row ->
        buildLegacyQueryRow(queryResult.columns, row)
    }

private fun buildLegacyQueryRow(
    columns: List<QueryColumn>,
    row: List<JsonElement?>,
): Map<String, String?> =
    columns.indices.associate { index ->
        columns[index].name to row[index]?.jsonPrimitive?.content
    }

private fun applyLegacyRowOverlay(
    columns: List<QueryColumn>,
    originalRow: List<JsonElement?>,
    effectiveLegacyRow: Map<String, String?>,
): List<JsonElement?> =
    columns.indices.map { index ->
        val originalCell = originalRow[index]
        val effectiveValue = effectiveLegacyRow[columns[index].name]
        when {
            effectiveValue == null -> null
            originalCell == null -> JsonPrimitive(effectiveValue)
            !originalCell.jsonPrimitive.isString && originalCell.jsonPrimitive.content == effectiveValue -> originalCell
            else -> JsonPrimitive(effectiveValue)
        }
    }
