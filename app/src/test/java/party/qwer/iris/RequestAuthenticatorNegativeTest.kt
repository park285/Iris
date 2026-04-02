package party.qwer.iris

import kotlin.test.Test
import kotlin.test.assertEquals

class RequestAuthenticatorNegativeTest {
    @Test
    fun `rejects replayed nonce after successful finalize`() {
        val auth = RequestAuthenticator(nowEpochMs = { 1_700_000_000_000L })
        val body = """{"message":"hello"}"""
        val bodySha = sha256Hex(body.toByteArray())
        val signature =
            signIrisRequestWithBodyHash(
                secret = "secret",
                method = "POST",
                path = "/reply",
                timestamp = "1700000000000",
                nonce = "nonce-1",
                bodySha256Hex = bodySha,
            )

        val first =
            auth.authenticate(
                method = "POST",
                path = "/reply",
                body = body,
                bodySha256Hex = bodySha,
                expectedSecret = "secret",
                timestampHeader = "1700000000000",
                nonceHeader = "nonce-1",
                signatureHeader = signature,
            )
        val second =
            auth.authenticate(
                method = "POST",
                path = "/reply",
                body = body,
                bodySha256Hex = bodySha,
                expectedSecret = "secret",
                timestampHeader = "1700000000000",
                nonceHeader = "nonce-1",
                signatureHeader = signature,
            )

        assertEquals(AuthResult.AUTHORIZED, first)
        assertEquals(AuthResult.UNAUTHORIZED, second)
    }

    @Test
    fun `rejects when actual body hash differs from declared body hash`() {
        val auth = RequestAuthenticator(nowEpochMs = { 1_700_000_000_000L })
        val signedBody = """{"message":"hello"}"""
        val actualBody = """{"message":"tampered"}"""
        val declaredSha = sha256Hex(signedBody.toByteArray())
        val actualSha = sha256Hex(actualBody.toByteArray())
        val signature =
            signIrisRequestWithBodyHash(
                secret = "secret",
                method = "POST",
                path = "/reply",
                timestamp = "1700000000000",
                nonce = "nonce-2",
                bodySha256Hex = declaredSha,
            )

        val result =
            auth.authenticate(
                method = "POST",
                path = "/reply",
                body = actualBody,
                bodySha256Hex = actualSha,
                expectedSecret = "secret",
                timestampHeader = "1700000000000",
                nonceHeader = "nonce-2",
                signatureHeader = signature,
            )

        assertEquals(AuthResult.UNAUTHORIZED, result)
    }

    @Test
    fun `rejects stale timestamp`() {
        val auth = RequestAuthenticator(nowEpochMs = { 1_700_000_000_000L })
        val body = ""
        val bodySha = sha256Hex(body.toByteArray())
        val signature =
            signIrisRequestWithBodyHash(
                secret = "secret",
                method = "GET",
                path = "/config",
                timestamp = "1699990000000",
                nonce = "nonce-stale",
                bodySha256Hex = bodySha,
            )

        val result =
            auth.authenticate(
                method = "GET",
                path = "/config",
                body = body,
                bodySha256Hex = bodySha,
                expectedSecret = "secret",
                timestampHeader = "1699990000000",
                nonceHeader = "nonce-stale",
                signatureHeader = signature,
            )

        assertEquals(AuthResult.UNAUTHORIZED, result)
    }
}
