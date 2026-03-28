package party.qwer.iris

import kotlin.test.Test
import kotlin.test.assertEquals

class RequestAuthenticatorTest {
    @Test
    fun `rejects legacy bot token without signature`() {
        val authenticator = RequestAuthenticator(nowEpochMs = { 1_000L })

        val result =
            authenticator.authenticate(
                method = "GET",
                path = "/config",
                body = "",
                expectedSecret = "secret",
                timestampHeader = null,
                nonceHeader = null,
                signatureHeader = null,
            )

        assertEquals(AuthResult.UNAUTHORIZED, result)
    }

    @Test
    fun `accepts valid signed request`() {
        val authenticator = RequestAuthenticator(nowEpochMs = { 1_000L })
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
            authenticator.authenticate(
                method = "POST",
                path = "/config/endpoint",
                body = body,
                expectedSecret = "secret",
                timestampHeader = timestamp,
                nonceHeader = nonce,
                signatureHeader = signature,
            )

        assertEquals(AuthResult.AUTHORIZED, result)
    }

    @Test
    fun `rejects expired signed request`() {
        val authenticator = RequestAuthenticator(nowEpochMs = { 10_000L }, maxAgeMs = 1_000L)

        val result =
            authenticator.authenticate(
                method = "GET",
                path = "/config",
                body = "",
                expectedSecret = "secret",
                timestampHeader = "1000",
                nonceHeader = "nonce-1",
                signatureHeader = "bad",
            )

        assertEquals(AuthResult.UNAUTHORIZED, result)
    }

    @Test
    fun `rejects reused nonce`() {
        val authenticator = RequestAuthenticator(nowEpochMs = { 1_000L })
        val body = ""
        val timestamp = "1000"
        val nonce = "nonce-1"
        val signature =
            signIrisRequest(
                secret = "secret",
                method = "GET",
                path = "/config",
                timestamp = timestamp,
                nonce = nonce,
                body = body,
            )

        val first =
            authenticator.authenticate(
                method = "GET",
                path = "/config",
                body = body,
                expectedSecret = "secret",
                timestampHeader = timestamp,
                nonceHeader = nonce,
                signatureHeader = signature,
            )
        val second =
            authenticator.authenticate(
                method = "GET",
                path = "/config",
                body = body,
                expectedSecret = "secret",
                timestampHeader = timestamp,
                nonceHeader = nonce,
                signatureHeader = signature,
            )

        assertEquals(AuthResult.AUTHORIZED, first)
        assertEquals(AuthResult.UNAUTHORIZED, second)
    }

    @Test
    fun `signed GET binds query string into canonical request`() {
        val authenticator = RequestAuthenticator(nowEpochMs = { 1_000L })
        val timestamp = "1000"
        val nonce = "nonce-1"
        val canonicalTarget = "/rooms/42/stats?period=7d&limit=5"
        val signature =
            signIrisRequest(
                secret = "secret",
                method = "GET",
                path = canonicalTarget,
                timestamp = timestamp,
                nonce = nonce,
                body = "",
            )

        val authorized =
            authenticator.authenticate(
                method = "GET",
                path = canonicalTarget,
                body = "",
                expectedSecret = "secret",
                timestampHeader = timestamp,
                nonceHeader = nonce,
                signatureHeader = signature,
            )

        val tampered =
            authenticator.authenticate(
                method = "GET",
                path = "/rooms/42/stats?period=30d&limit=5",
                body = "",
                expectedSecret = "secret",
                timestampHeader = timestamp,
                nonceHeader = "nonce-2",
                signatureHeader = signature,
            )

        assertEquals(AuthResult.AUTHORIZED, authorized)
        assertEquals(AuthResult.UNAUTHORIZED, tampered)
    }
}
