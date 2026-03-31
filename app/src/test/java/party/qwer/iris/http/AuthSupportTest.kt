package party.qwer.iris.http

import party.qwer.iris.AuthResult
import party.qwer.iris.ConfigProvider
import party.qwer.iris.RequestAuthenticator
import party.qwer.iris.sha256Hex
import party.qwer.iris.signIrisRequest
import party.qwer.iris.signIrisRequestWithBodyHash
import kotlin.test.Test
import kotlin.test.assertEquals

class AuthSupportTest {
    private fun config(
        inboundSecret: String = "",
        botControlSecret: String = "",
    ): ConfigProvider =
        object : ConfigProvider {
            override val botId = 0L
            override val botName = ""
            override val botSocketPort = 0
            override val inboundSigningSecret = inboundSecret
            override val outboundWebhookToken = ""
            override val botControlToken = botControlSecret
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
                config = config(inboundSecret = "secret"),
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
                role = AuthSupport.SecretRole.INBOUND,
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
                config = config(inboundSecret = "secret"),
            )
        val body = """{"endpoint":"http://example"}"""

        val result =
            authSupport.authenticateRequest(
                role = AuthSupport.SecretRole.INBOUND,
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
    fun `authenticateRequest returns SERVICE_UNAVAILABLE when selected secret is blank`() {
        val authSupport =
            AuthSupport(
                authenticator = RequestAuthenticator(nowEpochMs = { 1_000L }),
                config = config(inboundSecret = "inbound-only"),
            )

        val result =
            authSupport.authenticateRequest(
                role = AuthSupport.SecretRole.BOT_CONTROL,
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
                config = config(botControlSecret = "secret"),
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
                role = AuthSupport.SecretRole.BOT_CONTROL,
                method = "GET",
                path = canonicalPath,
                bodySha256Hex = sha256Hex(body.toByteArray()),
                timestampHeader = timestamp,
                nonceHeader = nonce,
                signatureHeader = signature,
            )

        assertEquals(AuthResult.AUTHORIZED, result)
    }

    @Test
    fun `authenticateRequest distinguishes inbound and bot control secrets`() {
        val authSupport =
            AuthSupport(
                authenticator = RequestAuthenticator(nowEpochMs = { 1_000L }),
                config = config(inboundSecret = "inbound-secret", botControlSecret = "control-secret"),
            )
        val body = ""
        val timestamp = "1000"
        val nonce = "nonce-control"
        val path = "/reply-status/req-1"

        val botControlSignature =
            signIrisRequest(
                secret = "control-secret",
                method = "GET",
                path = path,
                timestamp = timestamp,
                nonce = nonce,
                body = body,
            )

        val inboundResult =
            authSupport.authenticateRequest(
                role = AuthSupport.SecretRole.INBOUND,
                method = "GET",
                path = path,
                bodySha256Hex = sha256Hex(body.toByteArray()),
                timestampHeader = timestamp,
                nonceHeader = nonce,
                signatureHeader = botControlSignature,
            )
        val controlResult =
            authSupport.authenticateRequest(
                role = AuthSupport.SecretRole.BOT_CONTROL,
                method = "GET",
                path = path,
                bodySha256Hex = sha256Hex(body.toByteArray()),
                timestampHeader = timestamp,
                nonceHeader = nonce,
                signatureHeader = botControlSignature,
            )

        assertEquals(AuthResult.UNAUTHORIZED, inboundResult)
        assertEquals(AuthResult.AUTHORIZED, controlResult)
    }

    @Test
    fun `precheckRequest rejects POST without declared body hash`() {
        val authSupport =
            AuthSupport(
                authenticator = RequestAuthenticator(nowEpochMs = { 1_000L }),
                config = config(inboundSecret = "secret"),
            )
        val body = """{"endpoint":"http://example"}"""
        val timestamp = "1000"
        val nonce = "nonce-preauth"
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
            authSupport.precheckRequest(
                role = AuthSupport.SecretRole.INBOUND,
                method = "POST",
                path = "/config/endpoint",
                timestampHeader = timestamp,
                nonceHeader = nonce,
                signatureHeader = signature,
                declaredBodySha256HexHeader = null,
            )

        assertEquals(AuthResult.UNAUTHORIZED, result.result)
    }

    @Test
    fun `precheckRequest and finalizeRequestAuthorization split signature verification from nonce commit`() {
        val authSupport =
            AuthSupport(
                authenticator = RequestAuthenticator(nowEpochMs = { 1_000L }),
                config = config(inboundSecret = "secret"),
            )
        val body = """{"endpoint":"http://example"}"""
        val bodySha256Hex = sha256Hex(body.toByteArray())
        val timestamp = "1000"
        val nonce = "nonce-precheck"
        val signature =
            signIrisRequestWithBodyHash(
                secret = "secret",
                method = "POST",
                path = "/config/endpoint",
                timestamp = timestamp,
                nonce = nonce,
                bodySha256Hex = bodySha256Hex,
            )

        val precheck =
            authSupport.precheckRequest(
                role = AuthSupport.SecretRole.INBOUND,
                method = "POST",
                path = "/config/endpoint",
                timestampHeader = timestamp,
                nonceHeader = nonce,
                signatureHeader = signature,
                declaredBodySha256HexHeader = bodySha256Hex,
            )
        assertEquals(AuthResult.AUTHORIZED, precheck.result)

        val mismatch =
            authSupport.finalizeRequestAuthorization(
                precheck = checkNotNull(precheck.precheck),
                actualBodySha256Hex = sha256Hex("""{"endpoint":"http://other"}""".toByteArray()),
            )
        assertEquals(AuthResult.UNAUTHORIZED, mismatch)

        val secondPrecheck =
            authSupport.precheckRequest(
                role = AuthSupport.SecretRole.INBOUND,
                method = "POST",
                path = "/config/endpoint",
                timestampHeader = timestamp,
                nonceHeader = nonce,
                signatureHeader = signature,
                declaredBodySha256HexHeader = bodySha256Hex,
            )
        assertEquals(AuthResult.AUTHORIZED, secondPrecheck.result)

        val authorized =
            authSupport.finalizeRequestAuthorization(
                precheck = checkNotNull(secondPrecheck.precheck),
                actualBodySha256Hex = bodySha256Hex,
            )
        assertEquals(AuthResult.AUTHORIZED, authorized)
    }
}
