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
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import party.qwer.iris.ConfigProvider
import party.qwer.iris.MessageSender
import party.qwer.iris.ReplyAdmissionResult
import party.qwer.iris.ReplyAdmissionStatus
import party.qwer.iris.VerifiedImagePayloadHandle
import party.qwer.iris.model.ReplyType
import party.qwer.iris.sha256Hex
import party.qwer.iris.signIrisRequestWithBodyHash
import party.qwer.iris.validateReplyMarkdownThreadMetadata
import party.qwer.iris.validateReplyThreadScope
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReplyRoutesTest {
    private val serverJson =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    // -- validateReplyThreadScope --

    @Test
    fun `validateReplyThreadScope returns null when no thread metadata`() {
        val result = validateReplyThreadScope(ReplyType.TEXT, threadId = null, threadScope = null)
        assertNull(result)
    }

    @Test
    fun `validateReplyThreadScope rejects zero scope`() {
        val ex =
            assertFailsWith<IllegalArgumentException> {
                validateReplyThreadScope(ReplyType.TEXT, threadId = 1L, threadScope = 0)
            }
        assertEquals("threadScope must be a positive integer", ex.message)
    }

    @Test
    fun `validateReplyThreadScope rejects scope without threadId`() {
        val ex =
            assertFailsWith<IllegalArgumentException> {
                validateReplyThreadScope(ReplyType.TEXT, threadId = null, threadScope = 3)
            }
        assertEquals("threadScope requires threadId unless scope is 1", ex.message)
    }

    @Test
    fun `validateReplyThreadScope accepts scope 1 without threadId`() {
        val result = validateReplyThreadScope(ReplyType.TEXT, threadId = null, threadScope = 1)
        assertEquals(1, result)
    }

    @Test
    fun `validateReplyThreadScope accepts valid scope with threadId`() {
        val result = validateReplyThreadScope(ReplyType.TEXT, threadId = 100L, threadScope = 2)
        assertEquals(2, result)
    }

    // -- validateReplyMarkdownThreadMetadata --

    @Test
    fun `validateReplyMarkdownThreadMetadata returns null when no metadata`() {
        val result = validateReplyMarkdownThreadMetadata(threadId = null, threadScope = null)
        assertNull(result)
    }

    @Test
    fun `validateReplyMarkdownThreadMetadata defaults scope to 2`() {
        val result = validateReplyMarkdownThreadMetadata(threadId = 123L, threadScope = null)
        assertEquals(2, result)
    }

    @Test
    fun `validateReplyMarkdownThreadMetadata rejects threadScope without threadId`() {
        assertFailsWith<IllegalArgumentException> {
            validateReplyMarkdownThreadMetadata(threadId = null, threadScope = 2)
        }
    }

    @Test
    fun `validateReplyMarkdownThreadMetadata accepts valid metadata`() {
        val result = validateReplyMarkdownThreadMetadata(threadId = 123L, threadScope = 3)
        assertEquals(3, result)
    }

    @Test
    fun `validateReplyMarkdownThreadMetadata rejects zero scope`() {
        assertFailsWith<IllegalArgumentException> {
            validateReplyMarkdownThreadMetadata(threadId = 123L, threadScope = 0)
        }
    }

    @Test
    fun `invalid reply signature is rejected before body read`() =
        testApplication {
            val bodyReads = AtomicInteger(0)
            application {
                install(ContentNegotiation) {
                    json(serverJson)
                }
                routing {
                    installReplyRoutes(
                        authSupport = AuthSupport(party.qwer.iris.RequestAuthenticator(), replyRouteConfig),
                        serverJson = serverJson,
                        notificationReferer = "kakaotalk://test",
                        messageSender = replyRouteMessageSender,
                        replyStatusProvider = null,
                        protectedBodyReader = { _, _ ->
                            bodyReads.incrementAndGet()
                            error("body should not be read before auth")
                        },
                    )
                }
            }

            val body = """{"room":"42","type":"TEXT","text":"hello"}"""
            val response =
                client.post("/reply") {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                    applySignedHeaders(path = "/reply", body = body, secret = "wrong-secret")
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertTrue(response.bodyAsText().lowercase().contains("unauthorized"))
            assertEquals(0, bodyReads.get())
        }

    private fun io.ktor.client.request.HttpRequestBuilder.applySignedHeaders(
        path: String,
        body: String,
        secret: String = replyRouteConfig.botControlToken,
    ) {
        val timestamp = System.currentTimeMillis().toString()
        val nonce = "nonce-$path-${body.length}"
        val bodySha256Hex = sha256Hex(body.toByteArray())
        val signature =
            signIrisRequestWithBodyHash(
                secret = secret,
                method = "POST",
                path = path,
                timestamp = timestamp,
                nonce = nonce,
                bodySha256Hex = bodySha256Hex,
            )
        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        header("X-Iris-Timestamp", timestamp)
        header("X-Iris-Nonce", nonce)
        header("X-Iris-Signature", signature)
        header("X-Iris-Body-Sha256", bodySha256Hex)
    }
}

private val replyRouteConfig =
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

private val replyRouteMessageSender =
    object : MessageSender {
        override suspend fun sendMessageSuspend(
            referer: String,
            chatId: Long,
            msg: String,
            threadId: Long?,
            threadScope: Int?,
            requestId: String?,
        ): ReplyAdmissionResult = ReplyAdmissionResult(ReplyAdmissionStatus.ACCEPTED, null)

        override suspend fun sendNativeMultiplePhotosHandlesSuspend(
            room: Long,
            imageHandles: List<VerifiedImagePayloadHandle>,
            threadId: Long?,
            threadScope: Int?,
            requestId: String?,
        ): ReplyAdmissionResult = ReplyAdmissionResult(ReplyAdmissionStatus.ACCEPTED, null)

        override suspend fun sendTextShareSuspend(
            room: Long,
            msg: String,
            requestId: String?,
        ): ReplyAdmissionResult = ReplyAdmissionResult(ReplyAdmissionStatus.ACCEPTED, null)

        override suspend fun sendReplyMarkdownSuspend(
            room: Long,
            msg: String,
            threadId: Long?,
            threadScope: Int?,
            requestId: String?,
        ): ReplyAdmissionResult = ReplyAdmissionResult(ReplyAdmissionStatus.ACCEPTED, null)
    }
