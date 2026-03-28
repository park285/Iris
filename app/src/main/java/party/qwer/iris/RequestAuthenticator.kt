package party.qwer.iris

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal enum class AuthResult {
    AUTHORIZED,
    UNAUTHORIZED,
    SERVICE_UNAVAILABLE,
}

internal class RequestAuthenticator(
    private val nowEpochMs: () -> Long = { Instant.now().toEpochMilli() },
    private val maxAgeMs: Long = 5 * 60 * 1000L,
) {
    private val nonceTimestamps = ConcurrentHashMap<String, Long>()

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
        if (expectedSecret.isBlank()) {
            return AuthResult.SERVICE_UNAVAILABLE
        }

        val timestamp = timestampHeader?.toLongOrNull() ?: return AuthResult.UNAUTHORIZED
        val nonce = nonceHeader?.takeIf { it.isNotBlank() } ?: return AuthResult.UNAUTHORIZED
        val signature = signatureHeader?.takeIf { it.isNotBlank() } ?: return AuthResult.UNAUTHORIZED
        val now = nowEpochMs()
        if (kotlin.math.abs(now - timestamp) > maxAgeMs) {
            return AuthResult.UNAUTHORIZED
        }
        purgeExpiredNonces(now)
        if (nonceTimestamps.putIfAbsent(nonce, now) != null) {
            return AuthResult.UNAUTHORIZED
        }

        val expectedSignature =
            signIrisRequestWithBodyHash(
                secret = expectedSecret,
                method = method,
                path = path,
                timestamp = timestamp.toString(),
                nonce = nonce,
                bodySha256Hex = bodySha256Hex,
            )

        return if (
            MessageDigest.isEqual(
                signature.toByteArray(StandardCharsets.UTF_8),
                expectedSignature.toByteArray(StandardCharsets.UTF_8),
            )
        ) {
            AuthResult.AUTHORIZED
        } else {
            AuthResult.UNAUTHORIZED
        }
    }

    private fun purgeExpiredNonces(now: Long) {
        val cutoff = now - maxAgeMs
        nonceTimestamps.entries.removeIf { (_, seenAt) -> seenAt < cutoff }
    }
}

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
    val canonical = listOf(method.uppercase(), path, timestamp, nonce, bodySha256Hex).joinToString("\n")
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
    return mac.doFinal(canonical.toByteArray(StandardCharsets.UTF_8)).joinToString("") { byte -> "%02x".format(byte) }
}

internal fun sha256Hex(bytes: ByteArray): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }
