package party.qwer.iris

import kotlin.test.Test
import kotlin.test.assertEquals

class RequestAuthenticatorTest {
    @Test
    fun `canonical request serializes protocol fields in signing order`() {
        val canonical =
            IrisCanonicalRequest(
                method = "post",
                target = "/reply?room=1",
                timestampMs = "1700000000000",
                nonce = "nonce-1",
                bodySha256Hex = "abc123",
            )

        assertEquals(
            "POST\n/reply?room=1\n1700000000000\nnonce-1\nabc123",
            canonical.serialize(),
        )
    }

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

    @Test
    fun `invalid signature does not consume nonce`() {
        val authenticator = RequestAuthenticator(nowEpochMs = { 1_000L })
        val body = ""
        val timestamp = "1000"
        val nonce = "nonce-reuse-after-bad-sig"
        val validSignature =
            signIrisRequest(
                secret = "secret",
                method = "GET",
                path = "/config",
                timestamp = timestamp,
                nonce = nonce,
                body = body,
            )

        val badResult =
            authenticator.authenticate(
                method = "GET",
                path = "/config",
                body = body,
                expectedSecret = "secret",
                timestampHeader = timestamp,
                nonceHeader = nonce,
                signatureHeader = "invalid-signature",
            )
        assertEquals(AuthResult.UNAUTHORIZED, badResult)

        val goodResult =
            authenticator.authenticate(
                method = "GET",
                path = "/config",
                body = body,
                expectedSecret = "secret",
                timestampHeader = timestamp,
                nonceHeader = nonce,
                signatureHeader = validSignature,
            )
        assertEquals(AuthResult.AUTHORIZED, goodResult)
    }

    @Test
    fun `preverify and finalize authorize valid signed request`() {
        val authenticator = RequestAuthenticator(nowEpochMs = { 1_000L })
        val body = """{"endpoint":"http://example"}"""
        val bodySha256Hex = sha256Hex(body.toByteArray())
        val timestamp = "1000"
        val nonce = "nonce-preverify"
        val signature =
            signIrisRequestWithBodyHash(
                secret = "secret",
                method = "POST",
                path = "/config/endpoint",
                timestamp = timestamp,
                nonce = nonce,
                bodySha256Hex = bodySha256Hex,
            )

        val preverify =
            authenticator.preverify(
                method = "POST",
                path = "/config/endpoint",
                declaredBodySha256Hex = bodySha256Hex,
                expectedSecret = "secret",
                timestampHeader = timestamp,
                nonceHeader = nonce,
                signatureHeader = signature,
            )
        assertEquals(AuthResult.AUTHORIZED, preverify.result)

        val finalize =
            authenticator.finalizeAuthorized(
                precheck = checkNotNull(preverify.precheck),
                actualBodySha256Hex = bodySha256Hex,
            )
        assertEquals(AuthResult.AUTHORIZED, finalize)
    }

    @Test
    fun `preverify reserves nonce before finalize completes`() {
        val authenticator = RequestAuthenticator(nowEpochMs = { 1_000L })
        val body = """{"endpoint":"http://example"}"""
        val bodySha256Hex = sha256Hex(body.toByteArray())
        val timestamp = "1000"
        val nonce = "nonce-reserved"
        val signature =
            signIrisRequestWithBodyHash(
                secret = "secret",
                method = "POST",
                path = "/config/endpoint",
                timestamp = timestamp,
                nonce = nonce,
                bodySha256Hex = bodySha256Hex,
            )

        val first =
            authenticator.preverify(
                method = "POST",
                path = "/config/endpoint",
                declaredBodySha256Hex = bodySha256Hex,
                expectedSecret = "secret",
                timestampHeader = timestamp,
                nonceHeader = nonce,
                signatureHeader = signature,
            )
        val second =
            authenticator.preverify(
                method = "POST",
                path = "/config/endpoint",
                declaredBodySha256Hex = bodySha256Hex,
                expectedSecret = "secret",
                timestampHeader = timestamp,
                nonceHeader = nonce,
                signatureHeader = signature,
            )

        assertEquals(AuthResult.AUTHORIZED, first.result)
        assertEquals(AuthResult.UNAUTHORIZED, second.result)
    }

    @Test
    fun `preverify nonce scope includes canonical target`() {
        val authenticator = RequestAuthenticator(nowEpochMs = { 1_000L })
        val body = ""
        val bodySha256Hex = sha256Hex(body.toByteArray())
        val timestamp = "1000"
        val nonce = "nonce-scoped"
        val firstSignature =
            signIrisRequestWithBodyHash(
                secret = "secret",
                method = "GET",
                path = "/config",
                timestamp = timestamp,
                nonce = nonce,
                bodySha256Hex = bodySha256Hex,
            )
        val secondSignature =
            signIrisRequestWithBodyHash(
                secret = "secret",
                method = "GET",
                path = "/rooms",
                timestamp = timestamp,
                nonce = nonce,
                bodySha256Hex = bodySha256Hex,
            )

        val first =
            authenticator.preverify(
                method = "GET",
                path = "/config",
                declaredBodySha256Hex = bodySha256Hex,
                expectedSecret = "secret",
                timestampHeader = timestamp,
                nonceHeader = nonce,
                signatureHeader = firstSignature,
            )
        val second =
            authenticator.preverify(
                method = "GET",
                path = "/rooms",
                declaredBodySha256Hex = bodySha256Hex,
                expectedSecret = "secret",
                timestampHeader = timestamp,
                nonceHeader = nonce,
                signatureHeader = secondSignature,
            )

        assertEquals(AuthResult.AUTHORIZED, first.result)
        assertEquals(AuthResult.AUTHORIZED, second.result)
    }

    @Test
    fun `body hash mismatch does not consume nonce before finalize succeeds`() {
        val authenticator = RequestAuthenticator(nowEpochMs = { 1_000L })
        val body = """{"endpoint":"http://example"}"""
        val bodySha256Hex = sha256Hex(body.toByteArray())
        val timestamp = "1000"
        val nonce = "nonce-hash-mismatch"
        val signature =
            signIrisRequestWithBodyHash(
                secret = "secret",
                method = "POST",
                path = "/config/endpoint",
                timestamp = timestamp,
                nonce = nonce,
                bodySha256Hex = bodySha256Hex,
            )

        val preverify =
            authenticator.preverify(
                method = "POST",
                path = "/config/endpoint",
                declaredBodySha256Hex = bodySha256Hex,
                expectedSecret = "secret",
                timestampHeader = timestamp,
                nonceHeader = nonce,
                signatureHeader = signature,
            )
        assertEquals(AuthResult.AUTHORIZED, preverify.result)

        val mismatch =
            authenticator.finalizeAuthorized(
                precheck = checkNotNull(preverify.precheck),
                actualBodySha256Hex = sha256Hex("""{"endpoint":"http://other"}""".toByteArray()),
            )
        assertEquals(AuthResult.UNAUTHORIZED, mismatch)

        val authenticated =
            authenticator.authenticate(
                method = "POST",
                path = "/config/endpoint",
                body = body,
                expectedSecret = "secret",
                timestampHeader = timestamp,
                nonceHeader = nonce,
                signatureHeader = signature,
            )
        assertEquals(AuthResult.AUTHORIZED, authenticated)
    }

    @Test
    fun `rejects new nonce when cache is at capacity`() {
        var now = 1_000L
        val authenticator =
            RequestAuthenticator(
                nowEpochMs = { now },
                maxAgeMs = 10_000L,
                maxNonceEntries = 3,
                purgeIntervalMs = 60_000L,
            )

        val first = authenticateSignedRequest(authenticator, now = now, nonce = "nonce-1")
        now += 1
        val second = authenticateSignedRequest(authenticator, now = now, nonce = "nonce-2")
        now += 1
        val third = authenticateSignedRequest(authenticator, now = now, nonce = "nonce-3")
        now += 1
        val fourth = authenticateSignedRequest(authenticator, now = now, nonce = "nonce-4")

        assertEquals(AuthResult.AUTHORIZED, first)
        assertEquals(AuthResult.AUTHORIZED, second)
        assertEquals(AuthResult.AUTHORIZED, third)
        assertEquals(AuthResult.UNAUTHORIZED, fourth)
        assertEquals(3, cachedNonceCount(authenticator))
    }

    @Test
    fun `same nonce can be reused with fresh timestamp`() {
        var now = 1_000L
        val authenticator =
            RequestAuthenticator(
                nowEpochMs = { now },
                maxAgeMs = 1_000L,
                maxNonceEntries = 10,
                purgeIntervalMs = 5_000L,
            )

        val initial = authenticateSignedRequest(authenticator, now = now, nonce = "nonce-1")
        assertEquals(AuthResult.AUTHORIZED, initial)

        now = 2_500L
        val filler = authenticateSignedRequest(authenticator, now = now, nonce = "nonce-2")
        val reusedWithFreshTimestamp = authenticateSignedRequest(authenticator, now = now, nonce = "nonce-1")

        assertEquals(AuthResult.AUTHORIZED, filler)
        assertEquals(AuthResult.AUTHORIZED, reusedWithFreshTimestamp)
    }

    private fun authenticateSignedRequest(
        authenticator: RequestAuthenticator,
        now: Long,
        nonce: String,
        method: String = "GET",
        path: String = "/config",
        body: String = "",
        secret: String = "secret",
    ): AuthResult {
        val timestamp = now.toString()
        val signature =
            signIrisRequest(
                secret = secret,
                method = method,
                path = path,
                timestamp = timestamp,
                nonce = nonce,
                body = body,
            )

        return authenticator.authenticate(
            method = method,
            path = path,
            body = body,
            expectedSecret = secret,
            timestampHeader = timestamp,
            nonceHeader = nonce,
            signatureHeader = signature,
        )
    }

    private fun cachedNonceCount(authenticator: RequestAuthenticator): Int {
        val field = RequestAuthenticator::class.java.getDeclaredField("nonceWindow")
        field.isAccessible = true
        return (field.get(authenticator) as NonceWindow).size()
    }
}
