package party.qwer.iris

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
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
import io.ktor.server.routing.routing
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import party.qwer.iris.http.AuthSupport
import party.qwer.iris.http.ReplyImageIngressPolicy
import party.qwer.iris.http.RuntimeConfigReadiness
import party.qwer.iris.http.installConfigRoutes
import party.qwer.iris.http.installHealthRoutes
import party.qwer.iris.http.installMemberRoutes
import party.qwer.iris.http.installQueryRoutes
import party.qwer.iris.http.installReplyRoutes
import party.qwer.iris.model.ApiErrorResponse
import party.qwer.iris.model.CommonErrorResponse
import party.qwer.iris.model.ImageBridgeHealthResult
import party.qwer.iris.model.ReplyStatusSnapshot
import party.qwer.iris.model.ReplyType
import party.qwer.iris.persistence.RoomEventStore

internal const val NETTY_NO_NATIVE_PROPERTY = "io.netty.transport.noNative"

internal fun enforceNettyNioTransport(): Boolean {
    if (System.getProperty(NETTY_NO_NATIVE_PROPERTY) == "true") {
        return false
    }
    System.setProperty(NETTY_NO_NATIVE_PROPERTY, "true")
    return true
}

internal class IrisServer(
    private val configManager: ConfigManager,
    private val notificationReferer: String,
    private val messageSender: MessageSender,
    private val replyImageIngressPolicy: ReplyImageIngressPolicy = ReplyImageIngressPolicy.fromEnv(),
    private val bridgeHealthProvider: (() -> ImageBridgeHealthResult?)? = null,
    private val configReadinessProvider: (() -> RuntimeConfigReadiness)? = null,
    private val replyStatusProvider: ((String) -> ReplyStatusSnapshot?)? = null,
    private val memberRepo: MemberRepository? = null,
    private val sseEventBus: SseEventBus? = null,
    private val roomEventStore: RoomEventStore? = null,
    private val chatRoomIntrospectProvider: ((Long) -> String)? = null,
    private val memberNicknameDiagnosticsProvider: ((Long) -> MemberNicknameDiagnostics?)? = null,
    private val bindHost: String = DEFAULT_BIND_HOST,
    private val nettyWorkerThreads: Int = DEFAULT_NETTY_WORKER_THREADS,
) {
    private val authSupport = AuthSupport(RequestAuthenticator(), configManager)
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
                        configurePlugins()
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

    private fun Application.configurePlugins() {
        install(ContentNegotiation) {
            json(serverJson)
        }
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
            installHealthRoutes(
                authSupport = authSupport,
                bridgeHealthProvider = bridgeHealthProvider,
                configReadinessProvider = configReadinessProvider,
                chatRoomIntrospectProvider = chatRoomIntrospectProvider,
                memberNicknameDiagnosticsProvider = memberNicknameDiagnosticsProvider,
                readyVerbose = configManager.readyVerbose(),
            )
            installConfigRoutes(authSupport, configManager, serverJson)
            installReplyRoutes(
                authSupport = authSupport,
                serverJson = serverJson,
                notificationReferer = notificationReferer,
                messageSender = messageSender,
                replyStatusProvider = replyStatusProvider,
                replyImageIngressPolicy = replyImageIngressPolicy,
            )
            installQueryRoutes(authSupport, serverJson, memberRepo)
            installMemberRoutes(authSupport, memberRepo, sseEventBus, roomEventStore)
        }
    }

    companion object {
        private const val SERVER_GRACE_PERIOD_MS = 1_000L
        private const val SERVER_STOP_TIMEOUT_MS = 5_000L
        private const val DEFAULT_BIND_HOST = "127.0.0.1"
        private const val DEFAULT_NETTY_WORKER_THREADS = 2
        private const val DEFAULT_ENABLE_HTTP2 = true
        private const val DEFAULT_ENABLE_H2C = true

        internal fun runtimeHttp2Enabled(): Boolean = DEFAULT_ENABLE_HTTP2

        internal fun runtimeH2cEnabled(): Boolean = DEFAULT_ENABLE_H2C
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
