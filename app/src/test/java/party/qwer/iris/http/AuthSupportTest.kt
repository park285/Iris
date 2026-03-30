package party.qwer.iris.http

import party.qwer.iris.AuthResult
import party.qwer.iris.ConfigProvider
import party.qwer.iris.RequestAuthenticator
import party.qwer.iris.sha256Hex
import party.qwer.iris.signIrisRequest
import kotlin.test.Test
import kotlin.test.assertEquals

class AuthSupportTest {
    private fun config(secret: String): ConfigProvider =
        object : ConfigProvider {
            override val botId = 0L
            override val botName = ""
            override val botSocketPort = 0
            override val inboundSigningSecret = secret
            override val outboundWebhookToken = ""
            override val botControlToken = ""
            override val dbPollingRate = 1000L
            override val messageSendRate = 0L
            override val messageSendJitterMax = 0L

            override fun webhookEndpointFor(route: String): String = ""
        }

    @Test
    fun `authenticateRequest returns AUTHORIZED for valid signature`() {
        val authSupport =
            AuthSupport(
                authenticator = RequestAuthenticator(nowEpochMs = { 1_000L }),
                config = config(secret = "secret"),
            )
        val body = """{"endpoint":"http://example"}"""
        val timestamp = "1000"
        val nonce = "nonce-1"
        val signature =
            signIrisRequest(
                secret = "secret",
                method = "POST",
                path = "/config/endpoint",
                timestamp = timestamp,
                nonce = nonce,
                body = body,
            )

        val result =
            authSupport.authenticateRequest(
                method = "POST",
                path = "/config/endpoint",
                bodySha256Hex = sha256Hex(body.toByteArray()),
                timestampHeader = timestamp,
                nonceHeader = nonce,
                signatureHeader = signature,
            )

        assertEquals(AuthResult.AUTHORIZED, result)
    }

    @Test
    fun `authenticateRequest returns UNAUTHORIZED for invalid signature`() {
        val authSupport =
            AuthSupport(
                authenticator = RequestAuthenticator(nowEpochMs = { 1_000L }),
                config = config(secret = "secret"),
            )
        val body = """{"endpoint":"http://example"}"""

        val result =
            authSupport.authenticateRequest(
                method = "POST",
                path = "/config/endpoint",
                bodySha256Hex = sha256Hex(body.toByteArray()),
                timestampHeader = "1000",
                nonceHeader = "nonce-1",
                signatureHeader = "wrong-signature",
            )

        assertEquals(AuthResult.UNAUTHORIZED, result)
    }

    @Test
    fun `authenticateRequest returns SERVICE_UNAVAILABLE when secret is blank`() {
        val authSupport =
            AuthSupport(
                authenticator = RequestAuthenticator(nowEpochMs = { 1_000L }),
                config = config(secret = ""),
            )

        val result =
            authSupport.authenticateRequest(
                method = "GET",
                path = "/config",
                bodySha256Hex = sha256Hex(ByteArray(0)),
                timestampHeader = "1000",
                nonceHeader = "nonce-1",
                signatureHeader = "any-signature",
            )

        assertEquals(AuthResult.SERVICE_UNAVAILABLE, result)
    }

    @Test
    fun `authenticateRequest authorizes canonical query target`() {
        val authSupport =
            AuthSupport(
                authenticator = RequestAuthenticator(nowEpochMs = { 1_000L }),
                config = config(secret = "secret"),
            )
        val body = ""
        val timestamp = "1000"
        val nonce = "nonce-query"
        val canonicalPath = "/rooms/1/stats?limit=20&period=7d"
        val signature =
            signIrisRequest(
                secret = "secret",
                method = "GET",
                path = canonicalPath,
                timestamp = timestamp,
                nonce = nonce,
                body = body,
            )

        val result =
            authSupport.authenticateRequest(
                method = "GET",
                path = canonicalPath,
                bodySha256Hex = sha256Hex(body.toByteArray()),
                timestampHeader = timestamp,
                nonceHeader = nonce,
                signatureHeader = signature,
            )

        assertEquals(AuthResult.AUTHORIZED, result)
    }
}
