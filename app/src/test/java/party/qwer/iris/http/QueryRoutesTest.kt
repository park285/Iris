package party.qwer.iris.http

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import party.qwer.iris.ApiRequestException
import party.qwer.iris.ConfigProvider
import party.qwer.iris.MemberRepository
import party.qwer.iris.QueryExecutionResult
import party.qwer.iris.model.CommonErrorResponse
import party.qwer.iris.sha256Hex
import party.qwer.iris.signIrisRequestWithBodyHash
import party.qwer.iris.storage.KakaoDbSqlClient
import party.qwer.iris.storage.MemberIdentityQueries
import party.qwer.iris.storage.ObservedProfileQueries
import party.qwer.iris.storage.RoomDirectoryQueries
import party.qwer.iris.storage.RoomStatsQueries
import party.qwer.iris.storage.ThreadQueries
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QueryRoutesTest {
    private val serverJson =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    @Test
    fun `query room summary returns allowlisted room data`() =
        testApplication {
            application {
                install(ContentNegotiation) {
                    json(serverJson)
                }
                routing {
                    installQueryRoutes(
                        authSupport = AuthSupport(party.qwer.iris.RequestAuthenticator(), queryRouteConfig),
                        serverJson = serverJson,
                        memberRepo =
                            buildQueryRouteRepo(
                                queryRouteLegacyQuery { sql, args, _ ->
                                    when {
                                        sql.contains("FROM chat_rooms WHERE id = ?") && args?.firstOrNull() == "42" ->
                                            listOf(
                                                mapOf(
                                                    "id" to "42",
                                                    "type" to "DirectChat",
                                                    "link_id" to null,
                                                    "active_members_count" to "1",
                                                    "meta" to """[{"type":"3","content":"Room Title"}]""",
                                                    "members" to "[2]",
                                                    "blinded_member_ids" to null,
                                                ),
                                            )
                                        else -> emptyList()
                                    }
                                },
                            ),
                    )
                }
            }

            val body = """{"chatId":42}"""
            val response =
                client.post("/query/room-summary") {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                    applySignedHeaders("/query/room-summary", body)
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val text = response.bodyAsText()
            assertTrue(text.contains("Room Title"))
            assertTrue(text.contains(""""chatId":42"""))
        }

    @Test
    fun `query member stats returns allowlisted stats data`() =
        testApplication {
            application {
                install(ContentNegotiation) {
                    json(serverJson)
                }
                routing {
                    installQueryRoutes(
                        authSupport = AuthSupport(party.qwer.iris.RequestAuthenticator(), queryRouteConfig),
                        serverJson = serverJson,
                        memberRepo =
                            buildQueryRouteRepo(
                                queryRouteLegacyQuery { sql, args, _ ->
                                    when {
                                        sql.contains("FROM chat_logs WHERE chat_id = ? AND created_at >= ?") ->
                                            listOf(
                                                mapOf("user_id" to "2", "type" to "0", "cnt" to "7", "last_active" to "100"),
                                                mapOf("user_id" to "3", "type" to "1", "cnt" to "3", "last_active" to "90"),
                                            )
                                        sql.contains("SELECT link_id FROM chat_rooms WHERE id = ?") ->
                                            listOf(mapOf("link_id" to null))
                                        sql.contains("db2.friends") ->
                                            listOf(
                                                mapOf("id" to "2", "name" to "Alice", "enc" to "0"),
                                                mapOf("id" to "3", "name" to "Bob", "enc" to "0"),
                                            )
                                        else -> emptyList()
                                    }
                                },
                            ),
                    )
                }
            }

            val body = """{"chatId":42,"period":"all","limit":2,"minMessages":0}"""
            val response =
                client.post("/query/member-stats") {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                    applySignedHeaders("/query/member-stats", body)
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val text = response.bodyAsText()
            assertTrue(text.contains("Alice"))
            assertTrue(text.contains(""""topMembers""""))
        }

    @Test
    fun `query recent threads returns allowlisted thread data`() =
        testApplication {
            application {
                install(ContentNegotiation) {
                    json(serverJson)
                }
                routing {
                    installQueryRoutes(
                        authSupport = AuthSupport(party.qwer.iris.RequestAuthenticator(), queryRouteConfig),
                        serverJson = serverJson,
                        memberRepo =
                            buildQueryRouteRepo(
                                queryRouteLegacyQuery { sql, args, _ ->
                                    when {
                                        sql.contains("FROM chat_rooms WHERE id = ?") && args?.firstOrNull() == "42" ->
                                            listOf(
                                                mapOf(
                                                    "id" to "42",
                                                    "type" to "OpenChat",
                                                    "link_id" to "10",
                                                    "active_members_count" to "2",
                                                    "meta" to null,
                                                    "members" to "[2,3]",
                                                    "blinded_member_ids" to null,
                                                ),
                                            )
                                        sql.contains("WITH thread_stats AS") ->
                                            listOf(
                                                mapOf(
                                                    "thread_id" to "777",
                                                    "message_count" to "4",
                                                    "last_active_at" to "123",
                                                    "origin_message" to "hello",
                                                    "origin_user_id" to "2",
                                                    "origin_v" to """{"enc":0}""",
                                                ),
                                            )
                                        else -> emptyList()
                                    }
                                },
                            ),
                    )
                }
            }

            val body = """{"chatId":42}"""
            val response =
                client.post("/query/recent-threads") {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                    applySignedHeaders("/query/recent-threads", body)
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val text = response.bodyAsText()
            assertTrue(text.contains(""""threadId":"777""""))
            assertTrue(text.contains("hello"))
        }

    @Test
    fun `query recent messages returns decrypted allowlisted message data`() =
        testApplication {
            application {
                install(ContentNegotiation) {
                    json(serverJson)
                }
                routing {
                    installQueryRoutes(
                        authSupport = AuthSupport(party.qwer.iris.RequestAuthenticator(), queryRouteConfig),
                        serverJson = serverJson,
                        memberRepo =
                            buildQueryRouteRepo(
                                queryRouteLegacyQuery { sql, _, _ ->
                                    when {
                                        sql.contains("FROM chat_logs") && sql.contains("ORDER BY created_at DESC") ->
                                            listOf(
                                                mapOf(
                                                    "id" to "10",
                                                    "chat_id" to "42",
                                                    "user_id" to "7",
                                                    "message" to "cipher",
                                                    "type" to "1",
                                                    "created_at" to "1234",
                                                    "thread_id" to "99",
                                                    "v" to """{"enc":1}""",
                                                ),
                                            )
                                        else -> emptyList()
                                    }
                                },
                                decrypt = { enc, value, userId -> "dec:$enc:$userId:$value" },
                            ),
                    )
                }
            }

            val body = """{"chatId":42,"limit":10}"""
            val response =
                client.post("/query/recent-messages") {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                    applySignedHeaders("/query/recent-messages", body)
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val text = response.bodyAsText()
            assertTrue(text.contains(""""chatId":42"""))
            assertTrue(text.contains(""""message":"dec:1:7:cipher""""))
            assertTrue(text.contains(""""threadId":99"""))
        }

    @Test
    fun `query recent messages accepts cursor fields`() =
        testApplication {
            var capturedSql: String? = null
            var capturedArgs: Array<String?>? = null
            application {
                install(ContentNegotiation) {
                    json(serverJson)
                }
                routing {
                    installQueryRoutes(
                        authSupport = AuthSupport(party.qwer.iris.RequestAuthenticator(), queryRouteConfig),
                        serverJson = serverJson,
                        memberRepo =
                            buildQueryRouteRepo(
                                queryRouteLegacyQuery { sql, args, _ ->
                                    when {
                                        sql.contains("FROM chat_logs") -> {
                                            capturedSql = sql
                                            capturedArgs = args
                                            listOf(
                                                mapOf(
                                                    "id" to "11",
                                                    "chat_id" to "42",
                                                    "user_id" to "7",
                                                    "message" to "cursor",
                                                    "type" to "0",
                                                    "created_at" to "1235",
                                                    "thread_id" to "99",
                                                    "v" to """{"enc":0}""",
                                                ),
                                            )
                                        }
                                        else -> emptyList()
                                    }
                                },
                            ),
                    )
                }
            }

            val body = """{"chatId":42,"limit":20,"afterId":10,"threadId":99}"""
            val response =
                client.post("/query/recent-messages") {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                    applySignedHeaders("/query/recent-messages", body)
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(capturedSql.orEmpty().contains("id > ?"))
            assertTrue(capturedSql.orEmpty().contains("thread_id = ?"))
            assertTrue(capturedSql.orEmpty().contains("ORDER BY id ASC"))
            assertEquals(listOf("42", "10", "99", "20"), capturedArgs?.toList())
        }

    @Test
    fun `query recent messages rejects both cursors`() =
        testApplication {
            application {
                install(ContentNegotiation) {
                    json(serverJson)
                }
                installQueryRouteStatusPages()
                routing {
                    installQueryRoutes(
                        authSupport = AuthSupport(party.qwer.iris.RequestAuthenticator(), queryRouteConfig),
                        serverJson = serverJson,
                        memberRepo = buildQueryRouteRepo(queryRouteLegacyQuery { _, _, _ -> emptyList() }),
                    )
                }
            }

            val body = """{"chatId":42,"limit":20,"afterId":10,"beforeId":30}"""
            val response =
                client.post("/query/recent-messages") {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                    applySignedHeaders("/query/recent-messages", body)
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("afterId and beforeId are mutually exclusive"))
        }

    @Test
    fun `raw query endpoint is removed`() =
        testApplication {
            application {
                install(ContentNegotiation) {
                    json(serverJson)
                }
                routing {
                    installQueryRoutes(
                        authSupport = AuthSupport(party.qwer.iris.RequestAuthenticator(), queryRouteConfig),
                        serverJson = serverJson,
                        memberRepo = buildQueryRouteRepo(queryRouteLegacyQuery { _, _, _ -> emptyList() }),
                    )
                }
            }

            val body = """{"query":"SELECT 1"}"""
            val response =
                client.post("/query") {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                    applySignedHeaders("/query", body)
                }

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun `query routes require bot control secret when it differs from inbound secret`() =
        testApplication {
            application {
                install(ContentNegotiation) {
                    json(serverJson)
                }
                routing {
                    installQueryRoutes(
                        authSupport = AuthSupport(party.qwer.iris.RequestAuthenticator(), queryRouteConfig),
                        serverJson = serverJson,
                        memberRepo = buildQueryRouteRepo(queryRouteLegacyQuery { _, _, _ -> emptyList() }),
                    )
                }
            }

            val body = """{"chatId":42}"""
            val response =
                client.post("/query/room-summary") {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                    applySignedHeaders("/query/room-summary", body, secret = queryRouteConfig.inboundSigningSecret)
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `invalid query signature is rejected before body read`() =
        testApplication {
            val bodyReads = AtomicInteger(0)
            application {
                install(ContentNegotiation) {
                    json(serverJson)
                }
                routing {
                    installQueryRoutes(
                        authSupport = AuthSupport(party.qwer.iris.RequestAuthenticator(), queryRouteConfig),
                        serverJson = serverJson,
                        memberRepo = buildQueryRouteRepo(queryRouteLegacyQuery { _, _, _ -> emptyList() }),
                        protectedBodyReader = { _, _ ->
                            bodyReads.incrementAndGet()
                            error("body should not be read before auth")
                        },
                    )
                }
            }

            val body = """{"chatId":42}"""
            val response =
                client.post("/query/room-summary") {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                    applySignedHeaders("/query/room-summary", body, secret = "wrong-secret")
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertEquals(0, bodyReads.get())
        }

    private fun io.ktor.client.request.HttpRequestBuilder.applySignedHeaders(
        path: String,
        body: String,
        secret: String = queryRouteConfig.botControlToken,
    ) {
        val timestamp = System.currentTimeMillis().toString()
        val nonce = "nonce-$path-${body.length}"
        val signature =
            signIrisRequestWithBodyHash(
                secret = secret,
                method = "POST",
                path = path,
                timestamp = timestamp,
                nonce = nonce,
                bodySha256Hex = sha256Hex(body.toByteArray()),
            )
        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        header("X-Iris-Timestamp", timestamp)
        header("X-Iris-Nonce", nonce)
        header("X-Iris-Signature", signature)
        header("X-Iris-Body-Sha256", sha256Hex(body.toByteArray()))
    }

    private fun io.ktor.server.application.Application.installQueryRouteStatusPages() {
        install(StatusPages) {
            exception<ApiRequestException> { call, cause ->
                call.respond(cause.status, CommonErrorResponse(message = cause.message))
            }
        }
    }
}

private val queryRouteConfig =
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

private fun queryRouteStubResult(
    columns: List<String>,
    rows: List<Map<String, String?>>,
): QueryExecutionResult {
    val cols =
        columns.map {
            party.qwer.iris.model
                .QueryColumn(name = it, sqliteType = "TEXT")
        }
    val jsonRows =
        rows.map { row ->
            columns.map { col ->
                row[col]?.let { JsonPrimitive(it) }
            }
        }
    return QueryExecutionResult(cols, jsonRows)
}

private fun queryRouteEmptyResult(): QueryExecutionResult = QueryExecutionResult(emptyList(), emptyList())

private fun queryRouteLegacyQuery(block: (String, Array<String?>?, Int) -> List<Map<String, String?>>): (String, Array<String?>?, Int) -> QueryExecutionResult =
    { sql, args, maxRows ->
        val rows = block(sql, args, maxRows)
        val columns =
            rows
                .firstOrNull()
                ?.keys
                ?.toList()
                .orEmpty()
        if (columns.isEmpty()) {
            queryRouteEmptyResult()
        } else {
            queryRouteStubResult(columns, rows)
        }
    }

private fun buildQueryRouteRepo(
    executeQueryTyped: (String, Array<String?>?, Int) -> QueryExecutionResult,
    decrypt: (Int, String, Long) -> String = { _, s, _ -> s },
    botId: Long = 1L,
): MemberRepository {
    val sqlClient = KakaoDbSqlClient(executeQueryTyped)
    return MemberRepository(
        roomDirectory = RoomDirectoryQueries(sqlClient),
        memberIdentity = MemberIdentityQueries(sqlClient, decrypt, botId),
        observedProfile = ObservedProfileQueries(sqlClient),
        roomStats = RoomStatsQueries(sqlClient),
        threadQueries = ThreadQueries(sqlClient),
        decrypt = decrypt,
        botId = botId,
    )
}
