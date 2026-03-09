package party.qwer.iris

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import party.qwer.iris.model.AotResponse
import party.qwer.iris.model.ApiResponse
import party.qwer.iris.model.CommonErrorResponse
import party.qwer.iris.model.ConfigRequest
import party.qwer.iris.model.ConfigResponse
import party.qwer.iris.model.DashboardStatusResponse
import party.qwer.iris.model.DecryptRequest
import party.qwer.iris.model.DecryptResponse
import party.qwer.iris.model.QueryRequest
import party.qwer.iris.model.QueryResponse
import party.qwer.iris.model.ReplyRequest
import party.qwer.iris.model.ReplyType

class IrisServer(
    private val kakaoDB: KakaoDB,
    private val dbObserver: DBObserver,
    private val observerHelper: ObserverHelper,
    private val notificationReferer: String,
) {
    private val serverJson =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    fun startServer() {
        embeddedServer(
            CIO,
            port = Configurable.botSocketPort,
            host = "0.0.0.0",
        ) {
            install(ContentNegotiation) {
                json(serverJson)
            }

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
                    IrisLogger.error("[IrisServer] Unhandled error: ${cause.message}")
                    cause.printStackTrace()
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        CommonErrorResponse(
                            message = "internal server error",
                        ),
                    )
                }
            }

            routing {
                route("/dashboard") {
                    get {
                        val html = PageRenderer.renderDashboard()
                        call.respondText(html, ContentType.Text.Html)
                    }

                    get("status") {
                        call.respond(
                            DashboardStatusResponse(
                                isObserving = dbObserver.isPollingThreadAlive,
                                statusMessage =
                                    if (dbObserver.isPollingThreadAlive) {
                                        "Observing database"
                                    } else {
                                        "Not observing database"
                                    },
                                lastLogs = observerHelper.lastChatLogs,
                            ),
                        )
                    }
                }

                route("/config") {
                    get {
                        call.respond(
                            ConfigResponse(
                                botName = Configurable.botName,
                                webEndpoint = Configurable.defaultWebhookEndpoint,
                                botHttpPort = Configurable.botSocketPort,
                                dbPollingRate = Configurable.dbPollingRate,
                                messageSendRate = Configurable.messageSendRate,
                                botId = Configurable.botId,
                            ),
                        )
                    }

                    post("{name}") {
                        val name = call.parameters["name"]
                        val req = call.receive<ConfigRequest>()

                        when (name) {
                            "endpoint" -> {
                                val value = req.endpoint?.trim() ?: throw ApiRequestException("missing endpoint value")
                                if (value.isNotEmpty() && !value.startsWith("http://") && !value.startsWith("https://")) {
                                    throw ApiRequestException("endpoint must start with http:// or https://")
                                }
                                Configurable.defaultWebhookEndpoint = value
                            }

                            "botname" -> {
                                val value = req.botname
                                if (value.isNullOrBlank()) {
                                    throw ApiRequestException("missing or empty botname")
                                }
                                Configurable.botName = value
                            }

                            "dbrate" -> {
                                val value = req.rate ?: throw ApiRequestException("missing or invalid rate")
                                if (value < 1) {
                                    throw ApiRequestException("rate must be greater than 0")
                                }

                                Configurable.dbPollingRate = value
                            }

                            "sendrate" -> {
                                val value = req.rate ?: throw ApiRequestException("missing or invalid rate")
                                if (value < 0) {
                                    throw ApiRequestException("rate must be 0 or greater")
                                }

                                Configurable.messageSendRate = value
                            }

                            "botport" -> {
                                val value = req.port ?: throw ApiRequestException("missing or invalid port")

                                if (value < 1 || value > 65535) {
                                    throw ApiRequestException("invalid port number; port must be between 1 and 65535")
                                }

                                Configurable.botSocketPort = value
                            }

                            else -> {
                                throw ApiRequestException("unknown config '$name'")
                            }
                        }

                        Configurable.saveConfigNow()
                        call.respond(ApiResponse(success = true, message = "success"))
                    }
                }

                get("/aot") {
                    val aotToken = AuthProvider.getToken()

                    call.respond(
                        AotResponse(
                            success = true,
                            aot = Json.parseToJsonElement(aotToken.toString()).jsonObject,
                        ),
                    )
                }

                post("/reply") {
                    val replyRequest = call.receive<ReplyRequest>()
                    val roomId = replyRequest.room.toLongOrNull() ?: throw ApiRequestException("room must be a numeric string")
                    val threadId =
                        replyRequest.threadId?.let {
                            it.toLongOrNull() ?: throw ApiRequestException("threadId must be a numeric string")
                        }

                    when (replyRequest.type) {
                        ReplyType.TEXT ->
                            Replier.sendMessage(
                                notificationReferer,
                                roomId,
                                extractTextPayload(replyRequest),
                                threadId,
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

                    call.respond(ApiResponse(success = true, message = "success"))
                }

                post("/query") {
                    val queryRequest = call.receive<QueryRequest>()
                    if (queryRequest.query.isBlank()) {
                        throw ApiRequestException("query must not be blank")
                    }

                    try {
                        val rows =
                            kakaoDB.executeQuery(
                                queryRequest.query,
                                (queryRequest.bind?.map { it.content } ?: listOf()).toTypedArray(),
                                MAX_QUERY_ROWS + 1,
                            )
                        if (rows.size > MAX_QUERY_ROWS) {
                            throw ApiRequestException("query returned too many rows; limit is $MAX_QUERY_ROWS")
                        }

                        call.respond(
                            QueryResponse(
                                data =
                                    rows.map {
                                        KakaoDB.decryptRow(it)
                                    },
                            ),
                        )
                    } catch (e: Exception) {
                        IrisLogger.error(
                            "[IrisServer] Query execution failed: query=${queryRequest.query}, err=${e.message}",
                        )
                        throw ApiRequestException("query execution failed: ${e.message}")
                    }
                }

                post("/decrypt") {
                    val decryptRequest = call.receive<DecryptRequest>()
                    val plaintext =
                        KakaoDecrypt.decrypt(
                            decryptRequest.enc,
                            decryptRequest.b64_ciphertext,
                            decryptRequest.user_id ?: Configurable.botId,
                        )

                    call.respond(DecryptResponse(plain_text = plaintext))
                }
            }
        }.start(wait = true)
    }

    private fun extractTextPayload(replyRequest: ReplyRequest): String {
        return runCatching { replyRequest.data.jsonPrimitive.content }
            .getOrElse { throw ApiRequestException("text replies require string data") }
    }

    private fun extractSingleImagePayload(replyRequest: ReplyRequest): String {
        return extractTextPayload(replyRequest)
    }

    private fun extractImagePayloads(replyRequest: ReplyRequest): List<String> {
        return runCatching {
            replyRequest.data.jsonArray.map { element -> element.jsonPrimitive.content }
        }.getOrElse {
            throw ApiRequestException("image_multiple replies require a JSON array of base64 strings")
        }
    }

    private class ApiRequestException(
        override val message: String,
        val status: HttpStatusCode = HttpStatusCode.BadRequest,
    ) : RuntimeException(message)

    companion object {
        private const val MAX_QUERY_ROWS = 500
    }
}
