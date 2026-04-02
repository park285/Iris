package party.qwer.iris

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RequestAuthenticatorContractTest {
    @Test
    fun `shared auth vectors stay stable across canonical serialization and signing`() {
        val vectors = loadAuthVectors()
        assertTrue(vectors.size >= 7, "shared auth contract should keep at least 7 vectors")

        vectors.forEach { vector ->
            val canonical =
                IrisCanonicalRequest(
                    method = vector.method,
                    target = vector.target,
                    timestampMs = vector.timestampMs,
                    nonce = vector.nonce,
                    bodySha256Hex = vector.bodySha256Hex,
                )

            assertEquals(vector.canonicalRequest, canonical.serialize(), vector.name)
            assertEquals(
                vector.signature,
                signIrisRequestWithBodyHash(
                    secret = vector.secret,
                    method = vector.method,
                    path = vector.target,
                    timestamp = vector.timestampMs,
                    nonce = vector.nonce,
                    bodySha256Hex = vector.bodySha256Hex,
                ),
                vector.name,
            )
            assertEquals(
                AuthResult.AUTHORIZED,
                RequestAuthenticator(nowEpochMs = { vector.timestampMs.toLong() }).authenticate(
                    method = vector.method,
                    path = vector.target,
                    body = vector.body,
                    bodySha256Hex = vector.bodySha256Hex,
                    expectedSecret = vector.secret,
                    timestampHeader = vector.timestampMs,
                    nonceHeader = vector.nonce,
                    signatureHeader = vector.signature,
                ),
                vector.name,
            )
        }
    }
}
