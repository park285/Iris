package party.qwer.iris.http

import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
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
import party.qwer.iris.ImageFormat
import party.qwer.iris.MessageSender
import party.qwer.iris.ReplyAdmissionResult
import party.qwer.iris.ReplyAdmissionStatus
import party.qwer.iris.VerifiedImagePayloadHandle
import party.qwer.iris.model.CommonErrorResponse
import party.qwer.iris.model.ReplyImageMetadata
import party.qwer.iris.model.ReplyImagePartSpec
import party.qwer.iris.model.ReplyRequest
import party.qwer.iris.model.ReplyType
import party.qwer.iris.sha256Hex
import party.qwer.iris.signIrisRequestWithBodyHash
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReplyRoutesMultipartTest {
    private companion object {
        private fun pngBytes(vararg extra: Int): ByteArray =
            byteArrayOf(
                0x89.toByte(),
                0x50,
                0x4E,
                0x47,
                0x0D,
                0x0A,
                0x1A,
                0x0A,
                *extra.map { it.toByte() }.toByteArray(),
            )
    }

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
    fun `multipart image request authenticates signed manifest and forwards image bytes`() =
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

            val imageOne = pngBytes(1, 2, 3, 4)
            val imageTwo = pngBytes(5, 6, 7)
            val metadata =
                serverJson.encodeToString(
                    ReplyImageMetadata(
                        type = ReplyType.IMAGE_MULTIPLE,
                        room = "123456",
                        threadId = "789",
                        threadScope = 1,
                        images =
                            listOf(
                                ReplyImagePartSpec(
                                    index = 0,
                                    sha256Hex = sha256Hex(imageOne),
                                    byteLength = imageOne.size.toLong(),
                                    contentType = "image/png",
                                ),
                                ReplyImagePartSpec(
                                    index = 1,
                                    sha256Hex = sha256Hex(imageTwo),
                                    byteLength = imageTwo.size.toLong(),
                                    contentType = "image/png",
                                ),
                            ),
                    ),
                )
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
            assertEquals(1, sender.multiPhotoHandleCalls)
            assertEquals(123456L, sender.lastRoom)
            assertEquals(789L, sender.lastThreadId)
            assertEquals(1, sender.lastThreadScope)
            assertEquals(2, sender.lastImageBytes.size)
            assertContentEquals(imageOne, sender.lastImageBytes[0])
            assertContentEquals(imageTwo, sender.lastImageBytes[1])
        }

    @Test
    fun `multipart image handles remain readable after route returns when spill-backed`() =
        testApplication {
            val sender = RecordingMultipartMessageSender(consumeImmediately = false)
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

            val image = pngBytes(1, 2, 3, 4) + ByteArray(8192) { (it % 251).toByte() }
            val metadata =
                serverJson.encodeToString(
                    ReplyImageMetadata(
                        type = ReplyType.IMAGE,
                        room = "123456",
                        images =
                            listOf(
                                ReplyImagePartSpec(
                                    index = 0,
                                    sha256Hex = sha256Hex(image),
                                    byteLength = image.size.toLong(),
                                    contentType = "image/png",
                                ),
                            ),
                    ),
                )

            try {
                val response =
                    this.client.post("/reply") {
                        setBody(
                            MultiPartFormDataContent(
                                formData {
                                    append("metadata", metadata, headersOf(HttpHeaders.ContentType, "application/json"))
                                    append(
                                        "image",
                                        buildPacket { writeFully(image) },
                                        headersOf(
                                            HttpHeaders.ContentDisposition to listOf("form-data; name=\"image\"; filename=\"spill.png\""),
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
                assertContentEquals(image, sender.consumeRetainedHandles().single())
            } finally {
                sender.closeRetainedHandles()
            }
        }

    @Test
    fun `multipart sender rejection still transfers handle ownership`() =
        testApplication {
            val sender = RejectingMultipartMessageSender()
            val stagedHandle = CloseTrackingMultipartHandle(pngBytes(9, 9, 9, 9))
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
                        multipartImageStager =
                            MultipartImagePartStager { _, _, _ ->
                                stagedHandle
                            },
                    )
                }
            }

            val image = pngBytes(1, 2, 3, 4)
            val metadata =
                serverJson.encodeToString(
                    ReplyImageMetadata(
                        type = ReplyType.IMAGE,
                        room = "123456",
                        images =
                            listOf(
                                ReplyImagePartSpec(
                                    index = 0,
                                    sha256Hex = sha256Hex(image),
                                    byteLength = image.size.toLong(),
                                    contentType = "image/png",
                                ),
                            ),
                    ),
                )

            val response =
                this.client.post("/reply") {
                    setBody(
                        MultiPartFormDataContent(
                            formData {
                                append("metadata", metadata, headersOf(HttpHeaders.ContentType, "application/json"))
                                append(
                                    "image",
                                    buildPacket { writeFully(image) },
                                    headersOf(
                                        HttpHeaders.ContentDisposition to listOf("form-data; name=\"image\"; filename=\"reject.png\""),
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

            assertEquals(HttpStatusCode.TooManyRequests, response.status)
            assertEquals(1, sender.multiPhotoHandleCalls)
            assertTrue(stagedHandle.closed, "sender should own and close handles even when admission rejects")
            assertFalse(stagedHandle.pathExists(), "sender close should delete spill-backed handles after rejection")
        }

    @Test
    fun `multipart validation failure closes staged spill-backed handles before ownership transfer`() =
        testApplication {
            val sender = RecordingMultipartMessageSender()
            val stagedHandles = mutableListOf<VerifiedImagePayloadHandle>()
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
                        multipartImageStager =
                            MultipartImagePartStager { part, expectedPart, policy ->
                                stageMultipartImagePart(part, expectedPart, policy).also { stagedHandles += it }
                            },
                    )
                }
            }

            val image = pngBytes(1, 2, 3, 4) + ByteArray(8192) { (it % 251).toByte() }
            val metadata =
                serverJson.encodeToString(
                    ReplyImageMetadata(
                        type = ReplyType.IMAGE,
                        room = "123456",
                        threadId = "not-a-number",
                        images =
                            listOf(
                                ReplyImagePartSpec(
                                    index = 0,
                                    sha256Hex = sha256Hex(image),
                                    byteLength = image.size.toLong(),
                                    contentType = "image/png",
                                ),
                            ),
                    ),
                )

            val response =
                this.client.post("/reply") {
                    setBody(
                        MultiPartFormDataContent(
                            formData {
                                append("metadata", metadata, headersOf(HttpHeaders.ContentType, "application/json"))
                                append(
                                    "image",
                                    buildPacket { writeFully(image) },
                                    headersOf(
                                        HttpHeaders.ContentDisposition to listOf("form-data; name=\"image\"; filename=\"spill.png\""),
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

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(0, sender.multiPhotoCalls)
            assertEquals(1, stagedHandles.size)
            assertFails {
                stagedHandles.single().openInputStream().use { input -> input.readBytes() }
            }
        }

    @Test
    fun `multipart image larger than formFieldLimit is accepted`() =
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

            val image = pngBytes() + ByteArray(70_000)
            val metadata =
                serverJson.encodeToString(
                    ReplyImageMetadata(
                        type = ReplyType.IMAGE,
                        room = "123456",
                        images =
                            listOf(
                                ReplyImagePartSpec(
                                    index = 0,
                                    sha256Hex = sha256Hex(image),
                                    byteLength = image.size.toLong(),
                                    contentType = "image/png",
                                ),
                            ),
                    ),
                )
            val response =
                this.client.post("/reply") {
                    setBody(
                        MultiPartFormDataContent(
                            formData {
                                append("metadata", metadata, headersOf(HttpHeaders.ContentType, "application/json"))
                                append(
                                    "image",
                                    buildPacket { writeFully(image) },
                                    headersOf(
                                        HttpHeaders.ContentDisposition to listOf("form-data; name=\"image\"; filename=\"large.png\""),
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
            assertEquals(1, sender.multiPhotoHandleCalls)
            assertEquals(1, sender.lastImageBytes.size)
            assertContentEquals(image, sender.lastImageBytes.single())
        }

    @Test
    fun `multipart image request rejects image part before metadata`() =
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

            val image = pngBytes(1, 2, 3, 4)
            val metadata =
                serverJson.encodeToString(
                    ReplyImageMetadata(
                        type = ReplyType.IMAGE,
                        room = "123456",
                        images =
                            listOf(
                                ReplyImagePartSpec(
                                    index = 0,
                                    sha256Hex = sha256Hex(image),
                                    byteLength = image.size.toLong(),
                                    contentType = "image/png",
                                ),
                            ),
                    ),
                )
            val response =
                this.client.post("/reply") {
                    setBody(
                        MultiPartFormDataContent(
                            formData {
                                append(
                                    "image",
                                    buildPacket { writeFully(image) },
                                    headersOf(
                                        HttpHeaders.ContentDisposition to listOf("form-data; name=\"image\"; filename=\"one.png\""),
                                        HttpHeaders.ContentType to listOf("image/png"),
                                    ),
                                )
                                append("metadata", metadata, headersOf(HttpHeaders.ContentType, "application/json"))
                            },
                        ),
                    )
                    applySignedHeaders(
                        path = "/reply",
                        method = "POST",
                        bodySha256Hex = sha256Hex(metadata.toByteArray()),
                    )
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(0, sender.multiPhotoCalls)
        }

    @Test
    fun `multipart image request rejects digest mismatch`() =
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

            val advertised = pngBytes(1, 2, 3, 4)
            val actual = pngBytes(9, 9, 9, 9)
            val metadata =
                serverJson.encodeToString(
                    ReplyImageMetadata(
                        type = ReplyType.IMAGE,
                        room = "123456",
                        images =
                            listOf(
                                ReplyImagePartSpec(
                                    index = 0,
                                    sha256Hex = sha256Hex(advertised),
                                    byteLength = advertised.size.toLong(),
                                    contentType = "image/png",
                                ),
                            ),
                    ),
                )
            val response =
                this.client.post("/reply") {
                    setBody(
                        MultiPartFormDataContent(
                            formData {
                                append("metadata", metadata, headersOf(HttpHeaders.ContentType, "application/json"))
                                append(
                                    "image",
                                    buildPacket { writeFully(actual) },
                                    headersOf(
                                        HttpHeaders.ContentDisposition to listOf("form-data; name=\"image\"; filename=\"one.png\""),
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

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(0, sender.multiPhotoCalls)
        }

    @Test
    fun `multipart request with invalid auth is rejected before body buffering`() =
        testApplication {
            val sender = RecordingMultipartMessageSender()
            var stagedImageParts = 0
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
                        multipartImageStager =
                            MultipartImagePartStager { part, expectedPart, policy ->
                                stagedImageParts += 1
                                stageMultipartImagePart(part, expectedPart, policy)
                            },
                    )
                }
            }

            val image = pngBytes(1, 2, 3, 4)
            val metadata =
                serverJson.encodeToString(
                    ReplyImageMetadata(
                        type = ReplyType.IMAGE,
                        room = "123456",
                        images =
                            listOf(
                                ReplyImagePartSpec(
                                    index = 0,
                                    sha256Hex = sha256Hex(image),
                                    byteLength = image.size.toLong(),
                                    contentType = "image/png",
                                ),
                            ),
                    ),
                )
            val bodySha256Hex = sha256Hex(metadata.toByteArray())
            val response =
                this.client.post("/reply") {
                    setBody(
                        MultiPartFormDataContent(
                            formData {
                                append("metadata", metadata, headersOf(HttpHeaders.ContentType, "application/json"))
                                append(
                                    "image",
                                    buildPacket { writeFully(image) },
                                    headersOf(
                                        HttpHeaders.ContentDisposition to listOf("form-data; name=\"image\"; filename=\"one.png\""),
                                        HttpHeaders.ContentType to listOf("image/png"),
                                    ),
                                )
                            },
                        ),
                    )
                    headers.append("X-Iris-Timestamp", System.currentTimeMillis().toString())
                    headers.append("X-Iris-Nonce", "nonce-preauth-test")
                    headers.append("X-Iris-Body-Sha256", bodySha256Hex)
                    headers.append("X-Iris-Signature", "badbadbadbadbadbadbadbadbadbadbadbadbadbadbadbadbadbadbadbadbadb")
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertEquals(0, stagedImageParts)
            assertEquals(0, sender.multiPhotoCalls)
        }

    @Test
    fun `multipart image request rejects invalid signature before staging images`() =
        testApplication {
            val sender = RecordingMultipartMessageSender()
            var stagedImageParts = 0
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
                        multipartImageStager =
                            MultipartImagePartStager { part, expectedPart, policy ->
                                stagedImageParts += 1
                                stageMultipartImagePart(part, expectedPart, policy)
                            },
                    )
                }
            }

            val image = pngBytes(1, 2, 3, 4)
            val metadata =
                serverJson.encodeToString(
                    ReplyImageMetadata(
                        type = ReplyType.IMAGE,
                        room = "123456",
                        images =
                            listOf(
                                ReplyImagePartSpec(
                                    index = 0,
                                    sha256Hex = sha256Hex(image),
                                    byteLength = image.size.toLong(),
                                    contentType = "image/png",
                                ),
                            ),
                    ),
                )
            val response =
                this.client.post("/reply") {
                    setBody(
                        MultiPartFormDataContent(
                            formData {
                                append("metadata", metadata, headersOf(HttpHeaders.ContentType, "application/json"))
                                append(
                                    "image",
                                    buildPacket { writeFully(image) },
                                    headersOf(
                                        HttpHeaders.ContentDisposition to listOf("form-data; name=\"image\"; filename=\"one.png\""),
                                        HttpHeaders.ContentType to listOf("image/png"),
                                    ),
                                )
                            },
                        ),
                    )
                    headers.append("X-Iris-Timestamp", System.currentTimeMillis().toString())
                    headers.append("X-Iris-Nonce", "invalid")
                    headers.append("X-Iris-Signature", "invalid")
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertEquals(0, stagedImageParts)
            assertEquals(0, sender.multiPhotoCalls)
        }

    @Test
    fun `multipart image request rejects unknown binary even when digest matches`() =
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

            val blob = byteArrayOf(0x01, 0x02, 0x03, 0x04)
            val metadata =
                serverJson.encodeToString(
                    ReplyImageMetadata(
                        type = ReplyType.IMAGE,
                        room = "123456",
                        images =
                            listOf(
                                ReplyImagePartSpec(
                                    index = 0,
                                    sha256Hex = sha256Hex(blob),
                                    byteLength = blob.size.toLong(),
                                    contentType = "image/png",
                                ),
                            ),
                    ),
                )
            val response =
                this.client.post("/reply") {
                    setBody(
                        MultiPartFormDataContent(
                            formData {
                                append("metadata", metadata, headersOf(HttpHeaders.ContentType, "application/json"))
                                append(
                                    "image",
                                    buildPacket { writeFully(blob) },
                                    headersOf(
                                        HttpHeaders.ContentDisposition to listOf("form-data; name=\"image\"; filename=\"one.png\""),
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

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(0, sender.multiPhotoCalls)
        }

    @Test
    fun `multipart image request rejects unknown form part`() =
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

            val image = pngBytes(1, 2, 3, 4)
            val metadata =
                serverJson.encodeToString(
                    ReplyImageMetadata(
                        type = ReplyType.IMAGE,
                        room = "123456",
                        images =
                            listOf(
                                ReplyImagePartSpec(
                                    index = 0,
                                    sha256Hex = sha256Hex(image),
                                    byteLength = image.size.toLong(),
                                    contentType = "image/png",
                                ),
                            ),
                    ),
                )
            val response =
                this.client.post("/reply") {
                    setBody(
                        MultiPartFormDataContent(
                            formData {
                                append("metadata", metadata, headersOf(HttpHeaders.ContentType, "application/json"))
                                append("unexpected", "oops", headersOf(HttpHeaders.ContentType, "text/plain"))
                                append(
                                    "image",
                                    buildPacket { writeFully(image) },
                                    headersOf(
                                        HttpHeaders.ContentDisposition to listOf("form-data; name=\"image\"; filename=\"one.png\""),
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

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(0, sender.multiPhotoCalls)
        }

    @Test
    fun `multipart image request rejects unknown file part`() =
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

            val image = pngBytes(1, 2, 3, 4)
            val metadata =
                serverJson.encodeToString(
                    ReplyImageMetadata(
                        type = ReplyType.IMAGE,
                        room = "123456",
                        images =
                            listOf(
                                ReplyImagePartSpec(
                                    index = 0,
                                    sha256Hex = sha256Hex(image),
                                    byteLength = image.size.toLong(),
                                    contentType = "image/png",
                                ),
                            ),
                    ),
                )
            val response =
                this.client.post("/reply") {
                    setBody(
                        MultiPartFormDataContent(
                            formData {
                                append("metadata", metadata, headersOf(HttpHeaders.ContentType, "application/json"))
                                append(
                                    "attachment",
                                    buildPacket { writeFully(image) },
                                    headersOf(
                                        HttpHeaders.ContentDisposition to listOf("form-data; name=\"attachment\"; filename=\"one.png\""),
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

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(0, sender.multiPhotoCalls)
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
        headers.append("X-Iris-Body-Sha256", bodySha256Hex)
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

private class RecordingMultipartMessageSender(
    private val consumeImmediately: Boolean = true,
    private val result: ReplyAdmissionResult = ReplyAdmissionResult(ReplyAdmissionStatus.ACCEPTED),
) : MessageSender {
    var multiPhotoCalls = 0
    var multiPhotoHandleCalls = 0
    var lastRoom: Long? = null
    var lastThreadId: Long? = null
    var lastThreadScope: Int? = null
    var lastImageBytes: List<ByteArray> = emptyList()
    private var retainedHandles: List<VerifiedImagePayloadHandle> = emptyList()

    override suspend fun sendMessageSuspend(
        referer: String,
        chatId: Long,
        msg: String,
        threadId: Long?,
        threadScope: Int?,
        requestId: String?,
    ): ReplyAdmissionResult = error("unused")

    suspend fun sendNativePhotoBytesSuspend(
        room: Long,
        imageBytes: ByteArray,
        threadId: Long?,
        threadScope: Int?,
        requestId: String?,
    ): ReplyAdmissionResult = error("unused")

    suspend fun sendNativeMultiplePhotosBytesSuspend(
        room: Long,
        imageBytesList: List<ByteArray>,
        threadId: Long?,
        threadScope: Int?,
        requestId: String?,
    ): ReplyAdmissionResult = error("multipart image replies must stay on the handle path")

    override suspend fun sendNativeMultiplePhotosHandlesSuspend(
        room: Long,
        imageHandles: List<VerifiedImagePayloadHandle>,
        threadId: Long?,
        threadScope: Int?,
        requestId: String?,
    ): ReplyAdmissionResult {
        multiPhotoCalls += 1
        multiPhotoHandleCalls += 1
        lastRoom = room
        lastThreadId = threadId
        lastThreadScope = threadScope
        if (consumeImmediately) {
            lastImageBytes =
                imageHandles.map { handle ->
                    handle.openInputStream().use { input -> input.readBytes() }
                }
            imageHandles.forEach { handle ->
                runCatching { handle.close() }
            }
        } else {
            retainedHandles = imageHandles.toList()
        }
        return result
    }

    fun consumeRetainedHandles(): List<ByteArray> =
        retainedHandles.map { handle ->
            handle.openInputStream().use { input -> input.readBytes() }
        }

    fun closeRetainedHandles() {
        retainedHandles.forEach { handle ->
            runCatching { handle.close() }
        }
        retainedHandles = emptyList()
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

private class RejectingMultipartMessageSender : MessageSender {
    var multiPhotoHandleCalls = 0
        private set

    override suspend fun sendMessageSuspend(
        referer: String,
        chatId: Long,
        msg: String,
        threadId: Long?,
        threadScope: Int?,
        requestId: String?,
    ): ReplyAdmissionResult = error("unused")

    override suspend fun sendNativeMultiplePhotosHandlesSuspend(
        room: Long,
        imageHandles: List<VerifiedImagePayloadHandle>,
        threadId: Long?,
        threadScope: Int?,
        requestId: String?,
    ): ReplyAdmissionResult {
        multiPhotoHandleCalls += 1
        imageHandles.forEach { handle ->
            runCatching { handle.close() }
        }
        return ReplyAdmissionResult(ReplyAdmissionStatus.QUEUE_FULL, "queue full")
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

private class CloseTrackingMultipartHandle(
    private val bytes: ByteArray,
) : VerifiedImagePayloadHandle {
    private val spillPath =
        kotlin.io.path
            .createTempFile(
                prefix = "iris-multipart-test-",
                suffix = ".tmp",
            ).also { path ->
                java.nio.file.Files
                    .write(path, bytes)
            }

    var closed = false
        private set

    override val format = party.qwer.iris.ImageFormat.PNG
    override val contentType: String = "image/png"
    override val sha256Hex: String = sha256Hex(bytes)
    override val sizeBytes: Long = bytes.size.toLong()

    override fun openInputStream(): java.io.InputStream =
        java.nio.file.Files
            .newInputStream(spillPath)

    override fun close() {
        closed = true
        java.nio.file.Files
            .deleteIfExists(spillPath)
    }

    fun pathExists(): Boolean =
        java.nio.file.Files
            .exists(spillPath)
}
