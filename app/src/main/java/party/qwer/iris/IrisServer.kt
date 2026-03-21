package party.qwer.iris

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
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import party.qwer.iris.model.CommonErrorResponse
import party.qwer.iris.model.ConfigRequest
import party.qwer.iris.model.QueryRequest
import party.qwer.iris.model.QueryResponse
import party.qwer.iris.model.ReplyAcceptedResponse
import party.qwer.iris.model.ReplyRequest
import party.qwer.iris.model.ReplyType
import java.security.MessageDigest
import java.util.UUID

class IrisServer(
    private val kakaoDB: KakaoDB,
    private val notificationReferer: String,
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

    private fun createServer(): EmbeddedServer<*, *> =
        when (irisServerEngine()) {
            IrisServerEngine.NETTY -> {
                val transport = irisServerTransportConfig()
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
                embeddedServer(Netty, config) {
                    connector {
                        port = Configurable.botSocketPort
                        host = "0.0.0.0"
                    }
                    enableHttp2 = transport.enableHttp2
                    enableH2c = transport.enableH2c
                }
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
            configureQueryRoute()
        }
    }

    private fun Route.configureHealthRoutes() {
        get("/health") {
            call.respond(mapOf("status" to "ok"))
        }

        get("/ready") {
            call.respond(mapOf("status" to "ready"))
        }
    }

    private fun Route.configureConfigRoutes() {
        route("/config") {
            get {
                if (!requireBotToken(call)) {
                    return@get
                }
                call.respond(Configurable.configResponse())
            }

            post("{name}") {
                if (!requireBotToken(call)) {
                    return@post
                }

                val name = call.parameters["name"] ?: throw ApiRequestException("missing config name")
                val request = call.receive<ConfigRequest>()
                val updateOutcome = applyConfigUpdate(name, request)

                if (!Configurable.saveConfigNow()) {
                    throw ApiRequestException(
                        "failed to persist config update",
                        HttpStatusCode.InternalServerError,
                    )
                }

                call.respond(
                    Configurable.configUpdateResponse(
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

    private fun applyConfigUpdate(
        name: String,
        request: ConfigRequest,
    ): ConfigUpdateOutcome =
        when (name) {
            "endpoint" -> updateEndpointConfig(name, request)
            "dbrate" -> updateDbRateConfig(name, request)
            "sendrate" -> updateSendRateConfig(name, request)
            "botport" -> updateBotPortConfig(name, request)
            else -> throw ApiRequestException("unknown config '$name'")
        }

    private fun updateEndpointConfig(
        name: String,
        request: ConfigRequest,
    ): ConfigUpdateOutcome {
        val route = resolveEndpointRoute(request.route)
        val value = request.endpoint?.trim() ?: throw ApiRequestException("missing endpoint value")
        if (value.isNotEmpty() && !value.startsWith("http://") && !value.startsWith("https://")) {
            throw ApiRequestException("endpoint must start with http:// or https://")
        }

        Configurable.setWebhookEndpoint(route, value)
        return ConfigUpdateOutcome(
            name =
                if (route == DEFAULT_WEBHOOK_ROUTE) {
                    name
                } else {
                    "$name.$route"
                },
            applied = true,
            requiresRestart = false,
        )
    }

    private fun updateDbRateConfig(
        name: String,
        request: ConfigRequest,
    ): ConfigUpdateOutcome {
        val value = request.rate ?: throw ApiRequestException("missing or invalid rate")
        if (value < 1) {
            throw ApiRequestException("rate must be greater than 0")
        }

        Configurable.dbPollingRate = value
        return ConfigUpdateOutcome(
            name = name,
            applied = true,
            requiresRestart = false,
        )
    }

    private fun updateSendRateConfig(
        name: String,
        request: ConfigRequest,
    ): ConfigUpdateOutcome {
        val value = request.rate ?: throw ApiRequestException("missing or invalid rate")
        if (value < 0) {
            throw ApiRequestException("rate must be 0 or greater")
        }

        Configurable.messageSendRate = value
        return ConfigUpdateOutcome(
            name = name,
            applied = true,
            requiresRestart = false,
        )
    }

    private fun updateBotPortConfig(
        name: String,
        request: ConfigRequest,
    ): ConfigUpdateOutcome {
        val value = request.port ?: throw ApiRequestException("missing or invalid port")
        if (value < 1 || value > 65535) {
            throw ApiRequestException("invalid port number; port must be between 1 and 65535")
        }

        Configurable.botSocketPort = value
        return ConfigUpdateOutcome(
            name = name,
            applied = false,
            requiresRestart = true,
        )
    }

    private fun enqueueReply(replyRequest: ReplyRequest): ReplyAcceptedResponse {
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

        val admission = admitReply(replyRequest, roomId, threadId, threadScope)
        if (admission.status != ReplyAdmissionStatus.ACCEPTED) {
            requestRejected(
                admission.message ?: "reply request rejected",
                replyAdmissionHttpStatus(admission.status),
            )
        }

        return ReplyAcceptedResponse(
            requestId = "reply-${UUID.randomUUID()}",
            room = replyRequest.room,
            type = replyRequest.type,
        )
    }

    private fun admitReply(
        replyRequest: ReplyRequest,
        roomId: Long,
        threadId: Long?,
        threadScope: Int?,
    ): ReplyAdmissionResult =
        when (replyRequest.type) {
            ReplyType.TEXT ->
                Replier.sendMessage(
                    notificationReferer,
                    roomId,
                    extractTextPayload(replyRequest),
                    threadId,
                    threadScope,
                )

            ReplyType.IMAGE ->
                Replier.sendPhoto(
                    roomId,
                    extractSingleImagePayload(replyRequest),
                )

            ReplyType.IMAGE_MULTIPLE ->
                Replier.sendMultiplePhotos(
                    roomId,
                    extractImagePayloads(replyRequest),
                )
        }

    private suspend fun requireBotToken(call: ApplicationCall): Boolean {
        val expectedToken = Configurable.botToken
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
    }

    private fun executeQueryRequest(queryRequest: QueryRequest): QueryResponse {
        val rows =
            kakaoDB.executeQuery(
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
                    KakaoDB.decryptRow(it)
                },
        )
    }
}

private class ApiRequestException(
    override val message: String,
    val status: HttpStatusCode = HttpStatusCode.BadRequest,
) : RuntimeException(message)

private data class ConfigUpdateOutcome(
    val name: String,
    val applied: Boolean,
    val requiresRestart: Boolean,
)

internal enum class IrisServerEngine {
    NETTY,
}

internal fun irisServerEngine(): IrisServerEngine = IrisServerEngine.NETTY

internal data class IrisServerTransportConfig(
    val enableHttp2: Boolean,
    val enableH2c: Boolean,
)

internal fun irisServerTransportConfig(): IrisServerTransportConfig =
    IrisServerTransportConfig(
        enableHttp2 = true,
        enableH2c = true,
    )

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

private fun invalidRequest(message: String): Nothing = throw ApiRequestException(message)

private fun internalServerFailure(message: String): Nothing = throw ApiRequestException(message, HttpStatusCode.InternalServerError)

private fun resolveEndpointRoute(rawRoute: String?): String {
    val normalized = rawRoute?.trim().orEmpty()
    if (normalized.isEmpty()) {
        return DEFAULT_WEBHOOK_ROUTE
    }
    if (!normalized.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
        throw ApiRequestException("route must contain only letters, digits, '-' or '_'")
    }
    return normalized
}

private fun requestRejected(
    message: String,
    status: HttpStatusCode,
): Nothing = throw ApiRequestException(message, status)

private fun extractTextPayload(replyRequest: ReplyRequest): String =
    runCatching { replyRequest.data.jsonPrimitive.content }
        .getOrElse { invalidRequest("text replies require string data") }

private fun extractSingleImagePayload(replyRequest: ReplyRequest): String = extractTextPayload(replyRequest)

private fun extractImagePayloads(replyRequest: ReplyRequest): List<String> =
    runCatching {
        replyRequest.data.jsonArray.map { element -> element.jsonPrimitive.content }
    }.getOrElse {
        invalidRequest("image_multiple replies require a JSON array of base64 strings")
    }

private val SAFE_PRAGMAS = setOf(
    "table_info", "table_xinfo", "index_list", "index_info",
    "foreign_key_list", "compile_options", "database_list",
    "collation_list", "encoding", "page_size", "page_count",
    "max_page_count", "freelist_count",
)

private val WRITE_KEYWORD_PATTERN = Regex(
    """\b(INSERT|UPDATE|DELETE|DROP|CREATE|ALTER|ATTACH|DETACH|REPLACE|REINDEX|VACUUM)\b""",
    RegexOption.IGNORE_CASE,
)

internal fun isReadOnlyQuery(query: String): Boolean {
    val normalized = query.trimStart()
    if (normalized.isBlank()) return false
    val upper = normalized.uppercase()

    if (upper.startsWith("PRAGMA")) {
        val pragmaBody = normalized.substringAfter("PRAGMA", "").trimStart()
        val pragmaName = pragmaBody.split('(', '=', ' ', ';').first().trim().lowercase()
        return pragmaName in SAFE_PRAGMAS
    }

    if (!upper.startsWith("SELECT") && !upper.startsWith("WITH")) return false
    return !WRITE_KEYWORD_PATTERN.containsMatchIn(normalized)
}

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
