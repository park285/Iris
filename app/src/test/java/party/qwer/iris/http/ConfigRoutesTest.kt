package party.qwer.iris.http

import io.ktor.client.request.header
import io.ktor.client.request.post
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
import party.qwer.iris.ApiRequestException
import party.qwer.iris.ConfigManager
import party.qwer.iris.ConfigProvider
import party.qwer.iris.applyConfigUpdate
import party.qwer.iris.model.ConfigRequest
import party.qwer.iris.sha256Hex
import party.qwer.iris.signIrisRequestWithBodyHash
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfigRoutesTest {
    private val serverJson =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    @Test
    fun `rejects unknown config name`() {
        val configPath = Files.createTempFile("iris-config-routes-test", ".json")
        val configManager = ConfigManager(configPath = configPath.toString())

        val error =
            assertFailsWith<ApiRequestException> {
                applyConfigUpdate(
                    configManager = configManager,
                    name = "unknown",
                    request = ConfigRequest(endpoint = "http://example.com"),
                )
            }

        assertEquals("unknown config 'unknown'", error.message)
        configPath.deleteIfExists()
    }

    @Test
    fun `accepts valid endpoint`() {
        val configPath = Files.createTempFile("iris-config-routes-test", ".json")
        val configManager = ConfigManager(configPath = configPath.toString())

        val outcome =
            applyConfigUpdate(
                configManager = configManager,
                name = "endpoint",
                request = ConfigRequest(endpoint = "https://example.com/webhook"),
            )

        assertEquals("https://example.com/webhook", configManager.defaultWebhookEndpoint)
        assertTrue(outcome.applied)
        assertFalse(outcome.requiresRestart)
        configPath.deleteIfExists()
    }

    @Test
    fun `rejects invalid endpoint`() {
        val configPath = Files.createTempFile("iris-config-routes-test", ".json")
        val configManager = ConfigManager(configPath = configPath.toString())

        val error =
            assertFailsWith<ApiRequestException> {
                applyConfigUpdate(
                    configManager = configManager,
                    name = "endpoint",
                    request = ConfigRequest(endpoint = "ftp://example.com/webhook"),
                )
            }

        assertEquals("endpoint must start with http:// or https://", error.message)
        configPath.deleteIfExists()
    }

    @Test
    fun `botport sets requiresRestart`() {
        val configPath = Files.createTempFile("iris-config-routes-test", ".json")
        val configManager = ConfigManager(configPath = configPath.toString())
        val originalPort = configManager.botSocketPort

        val outcome =
            applyConfigUpdate(
                configManager = configManager,
                name = "botport",
                request = ConfigRequest(port = 4000),
            )

        val response = configManager.configResponse()

        assertEquals(originalPort, configManager.botSocketPort)
        assertEquals(4000, response.user.botHttpPort)
        assertEquals(originalPort, response.applied.botHttpPort)
        assertFalse(outcome.applied)
        assertTrue(outcome.requiresRestart)
        configPath.deleteIfExists()
    }

    @Test
    fun `invalid config signature is rejected before body read`() =
        testApplication {
            val configPath = Files.createTempFile("iris-config-routes-http-test", ".json")
            val configManager = ConfigManager(configPath = configPath.toString())
            val bodyReads = AtomicInteger(0)
            try {
                application {
                    install(ContentNegotiation) {
                        json(serverJson)
                    }
                    routing {
                        installConfigRoutes(
                            authSupport = AuthSupport(party.qwer.iris.RequestAuthenticator(), configRouteConfig),
                            configManager = configManager,
                            serverJson = serverJson,
                            protectedBodyReader = { _, _ ->
                                bodyReads.incrementAndGet()
                                error("body should not be read before auth")
                            },
                        )
                    }
                }

                val body = """{"endpoint":"https://example.com/webhook"}"""
                val response =
                    client.post("/config/endpoint") {
                        contentType(ContentType.Application.Json)
                        setBody(body)
                        applySignedHeaders(path = "/config/endpoint", body = body, secret = "wrong-secret")
                    }

                assertEquals(HttpStatusCode.Unauthorized, response.status)
                assertEquals(0, bodyReads.get())
            } finally {
                configPath.deleteIfExists()
            }
        }

    private fun io.ktor.client.request.HttpRequestBuilder.applySignedHeaders(
        path: String,
        body: String,
        secret: String = configRouteConfig.inboundSigningSecret,
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

private val configRouteConfig =
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
