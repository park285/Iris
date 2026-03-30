package party.qwer.iris.http

import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.core.buildPacket
import io.ktor.utils.io.core.writeFully
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import party.qwer.iris.ApiRequestException
import party.qwer.iris.ConfigProvider
import party.qwer.iris.MessageSender
import party.qwer.iris.ReplyAdmissionResult
import party.qwer.iris.ReplyAdmissionStatus
import party.qwer.iris.model.CommonErrorResponse
import party.qwer.iris.model.ReplyImageMetadata
import party.qwer.iris.model.ReplyRequest
import party.qwer.iris.model.ReplyType
import party.qwer.iris.sha256Hex
import party.qwer.iris.signIrisRequestWithBodyHash
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class ReplyRoutesMultipartTest {
    private val serverJson =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    @Test
    fun `json image request is rejected`() =
        testApplication {
            val sender = RecordingMultipartMessageSender()
            application {
                install(ContentNegotiation) {
                    json(serverJson)
                }
                install(StatusPages) {
                    exception<ApiRequestException> { call, cause ->
                        call.respond(cause.status, CommonErrorResponse(message = cause.message))
                    }
                }
                routing {
                    installReplyRoutes(
                        authSupport = AuthSupport(party.qwer.iris.RequestAuthenticator(), multipartRouteConfig),
                        serverJson = serverJson,
                        notificationReferer = "ref",
                        messageSender = sender,
                        replyStatusProvider = null,
                    )
                }
            }

            val body =
                serverJson.encodeToString(
                    ReplyRequest(
                        type = ReplyType.IMAGE,
                        room = "123456",
                        data = JsonPrimitive("legacy-base64"),
                    ),
                )
            val response =
                this.client.post("/reply") {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                    applySignedHeaders(
                        path = "/reply",
                        method = "POST",
                        bodySha256Hex = sha256Hex(body.toByteArray()),
                    )
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(0, sender.multiPhotoCalls)
        }

    @Test
    fun `multipart image request uses metadata hash authentication and forwards image bytes`() =
        testApplication {
            val sender = RecordingMultipartMessageSender()
            application {
                install(ContentNegotiation) {
                    json(serverJson)
                }
                install(StatusPages) {
                    exception<ApiRequestException> { call, cause ->
                        call.respond(cause.status, CommonErrorResponse(message = cause.message))
                    }
                }
                routing {
                    installReplyRoutes(
                        authSupport = AuthSupport(party.qwer.iris.RequestAuthenticator(), multipartRouteConfig),
                        serverJson = serverJson,
                        notificationReferer = "ref",
                        messageSender = sender,
                        replyStatusProvider = null,
                    )
                }
            }

            val metadata =
                serverJson.encodeToString(
                    ReplyImageMetadata(
                        type = ReplyType.IMAGE_MULTIPLE,
                        room = "123456",
                        threadId = "789",
                        threadScope = 1,
                    ),
                )
            val imageOne = byteArrayOf(1, 2, 3, 4)
            val imageTwo = byteArrayOf(5, 6, 7)
            val response =
                this.client.post("/reply") {
                    setBody(
                        MultiPartFormDataContent(
                            formData {
                                append("metadata", metadata, headersOf(HttpHeaders.ContentType, "application/json"))
                                append(
                                    "image",
                                    buildPacket { writeFully(imageOne) },
                                    headersOf(
                                        HttpHeaders.ContentDisposition to listOf("form-data; name=\"image\"; filename=\"one.png\""),
                                        HttpHeaders.ContentType to listOf("image/png"),
                                    ),
                                )
                                append(
                                    "image",
                                    buildPacket { writeFully(imageTwo) },
                                    headersOf(
                                        HttpHeaders.ContentDisposition to listOf("form-data; name=\"image\"; filename=\"two.png\""),
                                        HttpHeaders.ContentType to listOf("image/png"),
                                    ),
                                )
                            },
                        ),
                    )
                    applySignedHeaders(
                        path = "/reply",
                        method = "POST",
                        bodySha256Hex = sha256Hex(metadata.toByteArray()),
                    )
                }

            assertEquals(HttpStatusCode.Accepted, response.status)
            assertEquals(1, sender.multiPhotoCalls)
            assertEquals(123456L, sender.lastRoom)
            assertEquals(789L, sender.lastThreadId)
            assertEquals(1, sender.lastThreadScope)
            assertEquals(2, sender.lastImageBytes.size)
            assertContentEquals(imageOne, sender.lastImageBytes[0])
            assertContentEquals(imageTwo, sender.lastImageBytes[1])
        }

    private fun io.ktor.client.request.HttpRequestBuilder.applySignedHeaders(
        path: String,
        method: String,
        bodySha256Hex: String,
    ) {
        val timestamp = System.currentTimeMillis().toString()
        val nonce = "nonce-$method-$path-$bodySha256Hex"
        val signature =
            signIrisRequestWithBodyHash(
                secret = multipartRouteConfig.botControlToken,
                method = method,
                path = path,
                timestamp = timestamp,
                nonce = nonce,
                bodySha256Hex = bodySha256Hex,
            )
        headers.append("X-Iris-Timestamp", timestamp)
        headers.append("X-Iris-Nonce", nonce)
        headers.append("X-Iris-Signature", signature)
    }
}

private val multipartRouteConfig =
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

private class RecordingMultipartMessageSender : MessageSender {
    var multiPhotoCalls = 0
    var lastRoom: Long? = null
    var lastThreadId: Long? = null
    var lastThreadScope: Int? = null
    var lastImageBytes: List<ByteArray> = emptyList()

    override suspend fun sendMessageSuspend(
        referer: String,
        chatId: Long,
        msg: String,
        threadId: Long?,
        threadScope: Int?,
        requestId: String?,
    ): ReplyAdmissionResult = error("unused")

    override suspend fun sendNativePhotoBytesSuspend(
        room: Long,
        imageBytes: ByteArray,
        threadId: Long?,
        threadScope: Int?,
        requestId: String?,
    ): ReplyAdmissionResult = error("unused")

    override suspend fun sendNativeMultiplePhotosBytesSuspend(
        room: Long,
        imageBytesList: List<ByteArray>,
        threadId: Long?,
        threadScope: Int?,
        requestId: String?,
    ): ReplyAdmissionResult {
        multiPhotoCalls += 1
        lastRoom = room
        lastThreadId = threadId
        lastThreadScope = threadScope
        lastImageBytes = imageBytesList
        return ReplyAdmissionResult(ReplyAdmissionStatus.ACCEPTED)
    }

    override suspend fun sendTextShareSuspend(
        room: Long,
        msg: String,
        requestId: String?,
    ): ReplyAdmissionResult = error("unused")

    override suspend fun sendReplyMarkdownSuspend(
        room: Long,
        msg: String,
        threadId: Long?,
        threadScope: Int?,
        requestId: String?,
    ): ReplyAdmissionResult = error("unused")
}
