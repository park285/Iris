package party.qwer.iris.http

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import party.qwer.iris.ConfigManager
import party.qwer.iris.ConfigProvider
import party.qwer.iris.MemberRepository
import party.qwer.iris.MessageSender
import party.qwer.iris.QueryExecutionResult
import party.qwer.iris.ReplyAdmissionResult
import party.qwer.iris.ReplyAdmissionStatus
import party.qwer.iris.VerifiedImagePayloadHandle
import party.qwer.iris.model.QueryColumn
import party.qwer.iris.model.ReplyLifecycleState
import party.qwer.iris.model.ReplyStatusSnapshot
import party.qwer.iris.sha256Hex
import party.qwer.iris.signIrisRequest
import party.qwer.iris.storage.KakaoDbSqlClient
import party.qwer.iris.storage.MemberIdentityQueries
import party.qwer.iris.storage.ObservedProfileQueries
import party.qwer.iris.storage.RoomDirectoryQueries
import party.qwer.iris.storage.RoomStatsQueries
import party.qwer.iris.storage.ThreadQueries
import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals

class RouteAuthRoleTest {
    private val serverJson =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    @Test
    fun `config routes accept inbound secret and reject bot control secret`() =
        testApplication {
            val configPath = Files.createTempFile("route-auth-config", ".json")
            configPath.toFile().writeText(
                """
                {
                  "inboundSigningSecret":"inbound-secret",
                  "botControlToken":"control-secret"
                }
                """.trimIndent(),
            )
            val manager = ConfigManager(configPath = configPath.toString())

            try {
                application {
                    install(ContentNegotiation) {
                        json(serverJson)
                    }
                    routing {
                        installConfigRoutes(
                            authSupport = AuthSupport(party.qwer.iris.RequestAuthenticator(), manager),
                            configManager = manager,
                            serverJson = serverJson,
                        )
                    }
                }

                val authorized =
                    client.get("/config") {
                        applySignedHeaders(path = "/config", secret = "inbound-secret")
                    }
                val rejected =
                    client.get("/config") {
                        applySignedHeaders(path = "/config", secret = "control-secret")
                    }

                assertEquals(HttpStatusCode.OK, authorized.status)
                assertEquals(HttpStatusCode.Unauthorized, rejected.status)
            } finally {
                configPath.deleteIfExists()
            }
        }

    @Test
    fun `reply status route requires bot control secret`() =
        testApplication {
            application {
                install(ContentNegotiation) {
                    json(serverJson)
                }
                routing {
                    installReplyRoutes(
                        authSupport = AuthSupport(party.qwer.iris.RequestAuthenticator(), routeRoleConfig),
                        serverJson = serverJson,
                        notificationReferer = "ref",
                        messageSender = noopMessageSender,
                        replyStatusProvider = {
                            ReplyStatusSnapshot(
                                requestId = "req-1",
                                state = ReplyLifecycleState.HANDOFF_COMPLETED,
                                updatedAtEpochMs = 1_000L,
                                detail = null,
                            )
                        },
                    )
                }
            }

            val authorized =
                client.get("/reply-status/req-1") {
                    applySignedHeaders(path = "/reply-status/req-1", secret = routeRoleConfig.botControlToken)
                }
            val rejected =
                client.get("/reply-status/req-1") {
                    applySignedHeaders(path = "/reply-status/req-1", secret = routeRoleConfig.inboundSigningSecret)
                }

            assertEquals(HttpStatusCode.OK, authorized.status)
            assertEquals(HttpStatusCode.Unauthorized, rejected.status)
        }

    @Test
    fun `member routes require bot control secret`() =
        testApplication {
            application {
                install(ContentNegotiation) {
                    json(serverJson)
                }
                routing {
                    installMemberRoutes(
                        authSupport = AuthSupport(party.qwer.iris.RequestAuthenticator(), routeRoleConfig),
                        memberRepo = buildMemberRepo(),
                        sseEventBus = null,
                    )
                }
            }

            val authorized =
                client.get("/rooms") {
                    applySignedHeaders(path = "/rooms", secret = routeRoleConfig.botControlToken)
                }
            val rejected =
                client.get("/rooms") {
                    applySignedHeaders(path = "/rooms", secret = routeRoleConfig.inboundSigningSecret)
                }

            assertEquals(HttpStatusCode.OK, authorized.status)
            assertEquals(HttpStatusCode.Unauthorized, rejected.status)
        }

    private fun io.ktor.client.request.HttpRequestBuilder.applySignedHeaders(
        path: String,
        secret: String,
    ) {
        val timestamp = System.currentTimeMillis().toString()
        val nonce = "nonce-$path-$timestamp"
        val signature =
            signIrisRequest(
                secret = secret,
                method = "GET",
                path = path,
                timestamp = timestamp,
                nonce = nonce,
                body = "",
            )
        header(HttpHeaders.Accept, ContentType.Application.Json.toString())
        header("X-Iris-Timestamp", timestamp)
        header("X-Iris-Nonce", nonce)
        header("X-Iris-Signature", signature)
        header("X-Iris-Body-Sha256", sha256Hex(ByteArray(0)))
    }
}

private val routeRoleConfig =
    object : ConfigProvider {
        override val botId = 1L
        override val botName = "iris"
        override val botSocketPort = 0
        override val inboundSigningSecret = "inbound-secret"
        override val outboundWebhookToken = ""
        override val botControlToken = "control-secret"
        override val dbPollingRate = 1000L
        override val messageSendRate = 0L
        override val messageSendJitterMax = 0L

        override fun webhookEndpointFor(route: String): String = ""
    }

private val noopMessageSender =
    object : MessageSender {
        override suspend fun sendMessageSuspend(
            referer: String,
            chatId: Long,
            msg: String,
            threadId: Long?,
            threadScope: Int?,
            requestId: String?,
        ): ReplyAdmissionResult = ReplyAdmissionResult(ReplyAdmissionStatus.ACCEPTED)

        suspend fun sendNativePhotoBytesSuspend(
            room: Long,
            imageBytes: ByteArray,
            threadId: Long?,
            threadScope: Int?,
            requestId: String?,
        ): ReplyAdmissionResult = ReplyAdmissionResult(ReplyAdmissionStatus.ACCEPTED)

        suspend fun sendNativeMultiplePhotosBytesSuspend(
            room: Long,
            imageBytesList: List<ByteArray>,
            threadId: Long?,
            threadScope: Int?,
            requestId: String?,
        ): ReplyAdmissionResult = ReplyAdmissionResult(ReplyAdmissionStatus.ACCEPTED)

        override suspend fun sendNativeMultiplePhotosHandlesSuspend(
            room: Long,
            imageHandles: List<VerifiedImagePayloadHandle>,
            threadId: Long?,
            threadScope: Int?,
            requestId: String?,
        ): ReplyAdmissionResult =
            try {
                sendNativeMultiplePhotosBytesSuspend(
                    room = room,
                    imageBytesList =
                        imageHandles.map { handle ->
                            handle.openInputStream().use { input -> input.readBytes() }
                        },
                    threadId = threadId,
                    threadScope = threadScope,
                    requestId = requestId,
                )
            } finally {
                imageHandles.forEach { handle ->
                    runCatching { handle.close() }
                }
            }

        override suspend fun sendTextShareSuspend(
            room: Long,
            msg: String,
            requestId: String?,
        ): ReplyAdmissionResult = ReplyAdmissionResult(ReplyAdmissionStatus.ACCEPTED)

        override suspend fun sendReplyMarkdownSuspend(
            room: Long,
            msg: String,
            threadId: Long?,
            threadScope: Int?,
            requestId: String?,
        ): ReplyAdmissionResult = ReplyAdmissionResult(ReplyAdmissionStatus.ACCEPTED)
    }

private fun buildMemberRepo(): MemberRepository {
    val executeQueryTyped: (String, Array<String?>?, Int) -> QueryExecutionResult =
        { sql, _, _ ->
            when {
                sql.contains("FROM chat_rooms") ->
                    QueryExecutionResult(
                        columns =
                            listOf(
                                QueryColumn("id", "INTEGER"),
                                QueryColumn("type", "TEXT"),
                                QueryColumn("link_id", "INTEGER"),
                                QueryColumn("active_members_count", "INTEGER"),
                                QueryColumn("meta", "TEXT"),
                                QueryColumn("members", "TEXT"),
                                QueryColumn("blinded_member_ids", "TEXT"),
                            ),
                        rows =
                            listOf(
                                listOf(
                                    kotlinx.serialization.json.JsonPrimitive("42"),
                                    kotlinx.serialization.json.JsonPrimitive("DirectChat"),
                                    null,
                                    kotlinx.serialization.json.JsonPrimitive("2"),
                                    kotlinx.serialization.json.JsonPrimitive("""[{"type":"3","content":"Room"}]"""),
                                    kotlinx.serialization.json.JsonPrimitive("[1,2]"),
                                    null,
                                ),
                            ),
                    )
                else -> QueryExecutionResult(emptyList(), emptyList())
            }
        }

    val sqlClient = KakaoDbSqlClient(executeQueryTyped)
    return MemberRepository(
        roomDirectory = RoomDirectoryQueries(sqlClient),
        memberIdentity = MemberIdentityQueries(sqlClient, decrypt = { _, value, _ -> value }, botId = 1L),
        observedProfile = ObservedProfileQueries(sqlClient),
        roomStats = RoomStatsQueries(sqlClient),
        threadQueries = ThreadQueries(sqlClient),
        decrypt = { _, value, _ -> value },
        botId = 1L,
    )
}
