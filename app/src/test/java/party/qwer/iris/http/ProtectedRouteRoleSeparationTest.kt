package party.qwer.iris.http

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
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
import party.qwer.iris.sha256Hex
import party.qwer.iris.signIrisRequestWithBodyHash
import party.qwer.iris.storage.KakaoDbSqlClient
import party.qwer.iris.storage.MemberIdentityQueries
import party.qwer.iris.storage.ObservedProfileQueries
import party.qwer.iris.storage.RoomDirectoryQueries
import party.qwer.iris.storage.RoomStatsQueries
import party.qwer.iris.storage.ThreadQueries
import party.qwer.iris.model.ImageBridgeHealthResult
import party.qwer.iris.model.ReplyLifecycleState
import party.qwer.iris.model.ReplyStatusSnapshot
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class ProtectedRouteRoleSeparationTest {
    private val serverJson =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    @Test
    fun `config routes require inbound secret and reject bot control secret`() =
        testApplication {
            val configPath = Files.createTempFile("iris-config-role-test", ".json")
            val configManager = ConfigManager(configPath = configPath.toString())
            try {
                application {
                    install(ContentNegotiation) {
                        json(serverJson)
                    }
                    routing {
                        installConfigRoutes(
                            authSupport = AuthSupport(party.qwer.iris.RequestAuthenticator(), roleSeparatedConfig),
                            configManager = configManager,
                            serverJson = serverJson,
                        )
                    }
                }

                val inboundResponse =
                    client.get("/config") {
                        applySignedHeaders(path = "/config", method = "GET", secret = roleSeparatedConfig.inboundSigningSecret)
                    }
                val botControlResponse =
                    client.get("/config") {
                        applySignedHeaders(path = "/config", method = "GET", secret = roleSeparatedConfig.botControlToken)
                    }

                assertEquals(HttpStatusCode.OK, inboundResponse.status)
                assertEquals(HttpStatusCode.Unauthorized, botControlResponse.status)
            } finally {
                Files.deleteIfExists(configPath)
            }
        }

    @Test
    fun `reply status route requires bot control secret and rejects inbound secret`() =
        testApplication {
            application {
                install(ContentNegotiation) {
                    json(serverJson)
                }
                routing {
                    installReplyRoutes(
                        authSupport = AuthSupport(party.qwer.iris.RequestAuthenticator(), roleSeparatedConfig),
                        serverJson = serverJson,
                        notificationReferer = "ref",
                        messageSender = noOpMessageSender,
                        replyStatusProvider = {
                            ReplyStatusSnapshot(
                                requestId = "req-1",
                                state = ReplyLifecycleState.HANDOFF_COMPLETED,
                                updatedAtEpochMs = 1L,
                            )
                        },
                    )
                }
            }

            val controlResponse =
                client.get("/reply-status/req-1") {
                    applySignedHeaders(path = "/reply-status/req-1", method = "GET", secret = roleSeparatedConfig.botControlToken)
                }
            val inboundResponse =
                client.get("/reply-status/req-1") {
                    applySignedHeaders(path = "/reply-status/req-1", method = "GET", secret = roleSeparatedConfig.inboundSigningSecret)
                }

            assertEquals(HttpStatusCode.OK, controlResponse.status)
            assertEquals(HttpStatusCode.Unauthorized, inboundResponse.status)
        }

    @Test
    fun `member routes require bot control secret and reject inbound secret`() =
        testApplication {
            application {
                install(ContentNegotiation) {
                    json(serverJson)
                }
                routing {
                    installMemberRoutes(
                        authSupport = AuthSupport(party.qwer.iris.RequestAuthenticator(), roleSeparatedConfig),
                        memberRepo = buildEmptyMemberRepository(),
                        sseEventBus = null,
                    )
                }
            }

            val controlResponse =
                client.get("/rooms") {
                    applySignedHeaders(path = "/rooms", method = "GET", secret = roleSeparatedConfig.botControlToken)
                }
            val inboundResponse =
                client.get("/rooms") {
                    applySignedHeaders(path = "/rooms", method = "GET", secret = roleSeparatedConfig.inboundSigningSecret)
                }

            assertEquals(HttpStatusCode.OK, controlResponse.status)
            assertEquals(HttpStatusCode.Unauthorized, inboundResponse.status)
        }

    @Test
    fun `health diagnostics routes require bot control secret and reject inbound secret`() =
        testApplication {
            application {
                install(ContentNegotiation) {
                    json(serverJson)
                }
                routing {
                    installHealthRoutes(
                        authSupport = AuthSupport(party.qwer.iris.RequestAuthenticator(), roleSeparatedConfig),
                        bridgeHealthProvider = {
                            ImageBridgeHealthResult(
                                reachable = true,
                                running = true,
                                specReady = true,
                                restartCount = 0,
                            )
                        },
                        chatRoomIntrospectProvider = { """{"chatId":$it}""" },
                    )
                }
            }

            val controlResponse =
                client.get("/diagnostics/bridge") {
                    applySignedHeaders(path = "/diagnostics/bridge", method = "GET", secret = roleSeparatedConfig.botControlToken)
                }
            val inboundResponse =
                client.get("/diagnostics/bridge") {
                    applySignedHeaders(path = "/diagnostics/bridge", method = "GET", secret = roleSeparatedConfig.inboundSigningSecret)
                }

            assertEquals(HttpStatusCode.OK, controlResponse.status)
            assertEquals(HttpStatusCode.Unauthorized, inboundResponse.status)
        }

    private fun io.ktor.client.request.HttpRequestBuilder.applySignedHeaders(
        path: String,
        method: String,
        secret: String,
        body: String = "",
    ) {
        val timestamp = System.currentTimeMillis().toString()
        val nonce = "nonce-$method-$path-${body.length}"
        val signature =
            signIrisRequestWithBodyHash(
                secret = secret,
                method = method,
                path = path,
                timestamp = timestamp,
                nonce = nonce,
                bodySha256Hex = sha256Hex(body.toByteArray()),
            )
        if (body.isNotEmpty()) {
            contentType(ContentType.Application.Json)
            setBody(body)
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        }
        header("X-Iris-Timestamp", timestamp)
        header("X-Iris-Nonce", nonce)
        header("X-Iris-Signature", signature)
    }
}

private val roleSeparatedConfig =
    object : ConfigProvider {
        override val botId = 1L
        override val botName = "iris"
        override val botSocketPort = 3000
        override val inboundSigningSecret = "inbound-secret"
        override val outboundWebhookToken = "outbound-secret"
        override val botControlToken = "control-secret"
        override val dbPollingRate = 1000L
        override val messageSendRate = 0L
        override val messageSendJitterMax = 0L

        override fun webhookEndpointFor(route: String): String = ""
    }

private val noOpMessageSender =
    object : MessageSender {
        override suspend fun sendMessageSuspend(
            referer: String,
            chatId: Long,
            msg: String,
            threadId: Long?,
            threadScope: Int?,
            requestId: String?,
        ) = error("unused")

        override suspend fun sendNativePhotoBytesSuspend(
            room: Long,
            imageBytes: ByteArray,
            threadId: Long?,
            threadScope: Int?,
            requestId: String?,
        ) = error("unused")

        override suspend fun sendNativeMultiplePhotosBytesSuspend(
            room: Long,
            imageBytesList: List<ByteArray>,
            threadId: Long?,
            threadScope: Int?,
            requestId: String?,
        ) = error("unused")

        override suspend fun sendTextShareSuspend(
            room: Long,
            msg: String,
            requestId: String?,
        ) = error("unused")

        override suspend fun sendReplyMarkdownSuspend(
            room: Long,
            msg: String,
            threadId: Long?,
            threadScope: Int?,
            requestId: String?,
        ) = error("unused")
    }

private fun buildEmptyMemberRepository(): MemberRepository {
    val sqlClient =
        KakaoDbSqlClient { _, _, _ ->
            party.qwer.iris.QueryExecutionResult(emptyList(), emptyList())
        }
    return MemberRepository(
        roomDirectory = RoomDirectoryQueries(sqlClient),
        memberIdentity = MemberIdentityQueries(sqlClient, decrypt = { _, s, _ -> s }, botId = 1L),
        observedProfile = ObservedProfileQueries(sqlClient),
        roomStats = RoomStatsQueries(sqlClient),
        threadQueries = ThreadQueries(sqlClient),
        decrypt = { _, s, _ -> s },
        botId = 1L,
    )
}
