package party.qwer.iris

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal enum class AuthResult {
    AUTHORIZED,
    UNAUTHORIZED,
    SERVICE_UNAVAILABLE,
}

internal data class SignaturePrecheck(
    val timestampEpochMs: Long,
    val nonce: String,
    val declaredBodySha256Hex: String,
    val nonceKey: String,
)

internal data class SignaturePreverifyResult(
    val result: AuthResult,
    val precheck: SignaturePrecheck? = null,
)

internal class RequestAuthenticator(
    private val nowEpochMs: () -> Long = { Instant.now().toEpochMilli() },
    private val maxAgeMs: Long = 5 * 60 * 1000L,
    private val maxNonceEntries: Int = 10_000,
    private val purgeIntervalMs: Long = 5_000L,
) {
    private val nonceWindow = NonceWindow(maxAgeMs, maxNonceEntries, purgeIntervalMs)

    fun authenticate(
        method: String,
        path: String,
        body: String,
        bodySha256Hex: String = sha256Hex(body.toByteArray(StandardCharsets.UTF_8)),
        expectedSecret: String,
        timestampHeader: String?,
        nonceHeader: String?,
        signatureHeader: String?,
    ): AuthResult {
        val preverifyResult =
            preverify(
                method = method,
                path = path,
                declaredBodySha256Hex = bodySha256Hex,
                expectedSecret = expectedSecret,
                timestampHeader = timestampHeader,
                nonceHeader = nonceHeader,
                signatureHeader = signatureHeader,
            )
        if (preverifyResult.result != AuthResult.AUTHORIZED) {
            return preverifyResult.result
        }

        return finalizeAuthorized(
            precheck = checkNotNull(preverifyResult.precheck),
            actualBodySha256Hex = bodySha256Hex,
        )
    }

    fun preverify(
        method: String,
        path: String,
        declaredBodySha256Hex: String?,
        expectedSecret: String,
        timestampHeader: String?,
        nonceHeader: String?,
        signatureHeader: String?,
    ): SignaturePreverifyResult {
        if (expectedSecret.isBlank()) {
            return SignaturePreverifyResult(AuthResult.SERVICE_UNAVAILABLE)
        }

        val timestamp = timestampHeader?.toLongOrNull() ?: return SignaturePreverifyResult(AuthResult.UNAUTHORIZED)
        val nonce = nonceHeader?.takeIf { it.isNotBlank() } ?: return SignaturePreverifyResult(AuthResult.UNAUTHORIZED)
        val signature = signatureHeader?.takeIf { it.isNotBlank() } ?: return SignaturePreverifyResult(AuthResult.UNAUTHORIZED)
        val normalizedDeclaredBodySha256 =
            normalizeDeclaredBodySha256(
                method = method,
                declaredBodySha256Hex = declaredBodySha256Hex,
            ) ?: return SignaturePreverifyResult(AuthResult.UNAUTHORIZED)
        val now = nowEpochMs()
        if (kotlin.math.abs(now - timestamp) > maxAgeMs) {
            return SignaturePreverifyResult(AuthResult.UNAUTHORIZED)
        }
        val expectedSignature =
            signIrisRequestWithBodyHash(
                secret = expectedSecret,
                method = method,
                path = path,
                timestamp = timestamp.toString(),
                nonce = nonce,
                bodySha256Hex = normalizedDeclaredBodySha256,
            )

        if (
            !MessageDigest.isEqual(
                signature.toByteArray(StandardCharsets.UTF_8),
                expectedSignature.toByteArray(StandardCharsets.UTF_8),
            )
        ) {
            return SignaturePreverifyResult(AuthResult.UNAUTHORIZED)
        }

        val nonceKey = buildNonceKey(method, path, timestamp, nonce)
        if (!nonceWindow.tryReserve(nonceKey, now)) {
            return SignaturePreverifyResult(AuthResult.UNAUTHORIZED)
        }

        return SignaturePreverifyResult(
            result = AuthResult.AUTHORIZED,
            precheck =
                SignaturePrecheck(
                    timestampEpochMs = timestamp,
                    nonce = nonce,
                    declaredBodySha256Hex = normalizedDeclaredBodySha256,
                    nonceKey = nonceKey,
                ),
        )
    }

    fun finalizeAuthorized(
        precheck: SignaturePrecheck,
        actualBodySha256Hex: String,
    ): AuthResult {
        val normalizedActualBodySha256 =
            normalizeBodySha256Hex(actualBodySha256Hex) ?: run {
                nonceWindow.release(precheck.nonceKey)
                return AuthResult.UNAUTHORIZED
            }
        if (!precheck.declaredBodySha256Hex.equals(normalizedActualBodySha256, ignoreCase = true)) {
            nonceWindow.release(precheck.nonceKey)
            return AuthResult.UNAUTHORIZED
        }

        if (!nonceWindow.commit(precheck.nonceKey, nowEpochMs())) {
            return AuthResult.UNAUTHORIZED
        }
        return AuthResult.AUTHORIZED
    }

    private fun normalizeDeclaredBodySha256(
        method: String,
        declaredBodySha256Hex: String?,
    ): String? {
        val normalizedHeader = normalizeBodySha256Hex(declaredBodySha256Hex)
        if (normalizedHeader != null) {
            return normalizedHeader
        }
        return when (method.uppercase()) {
            "GET", "HEAD" -> EMPTY_BODY_SHA256_HEX
            else -> null
        }
    }

    private fun normalizeBodySha256Hex(bodySha256Hex: String?): String? {
        val normalized = bodySha256Hex?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        if (normalized.length != SHA256_HEX_LENGTH) {
            return null
        }
        if (!normalized.all { it in HEX_DIGITS }) {
            return null
        }
        return normalized
    }
}

private const val SHA256_HEX_LENGTH = 64
private const val HEX_DIGITS = "0123456789abcdef"
private val EMPTY_BODY_SHA256_HEX = sha256Hex(ByteArray(0))

private fun buildNonceKey(
    method: String,
    path: String,
    timestamp: Long,
    nonce: String,
): String = "${method.uppercase()}\n$path\n$timestamp\n$nonce"

internal fun signIrisRequest(
    secret: String,
    method: String,
    path: String,
    timestamp: String,
    nonce: String,
    body: String,
): String = signIrisRequestWithBodyHash(secret, method, path, timestamp, nonce, sha256Hex(body.toByteArray(StandardCharsets.UTF_8)))

internal fun signIrisRequestWithBodyHash(
    secret: String,
    method: String,
    path: String,
    timestamp: String,
    nonce: String,
    bodySha256Hex: String,
): String {
    val canonical =
        IrisCanonicalRequest(
            method = method,
            target = path,
            timestampMs = timestamp,
            nonce = nonce,
            bodySha256Hex = bodySha256Hex,
        ).serialize()
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
    return mac.doFinal(canonical.toByteArray(StandardCharsets.UTF_8)).joinToString("") { byte -> "%02x".format(byte) }
}

internal fun sha256Hex(bytes: ByteArray): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }
