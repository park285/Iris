package party.qwer.iris.http

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import party.qwer.iris.requestRejected
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

private const val STREAM_BUFFER_BYTES = 8 * 1024

internal suspend fun readProtectedBody(
    call: ApplicationCall,
    maxBodyBytes: Int,
): StreamingBodyResult =
    readBodyWithStreamingDigest(
        bodyChannel = call.receiveChannel(),
        declaredContentLength = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull(),
        maxBodyBytes = maxBodyBytes,
    )

internal suspend fun readBodyWithStreamingDigest(
    bodyChannel: ByteReadChannel,
    declaredContentLength: Long?,
    maxBodyBytes: Int,
): StreamingBodyResult {
    if (declaredContentLength != null && declaredContentLength > maxBodyBytes) {
        requestRejected("request body too large", HttpStatusCode.PayloadTooLarge)
    }

    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(STREAM_BUFFER_BYTES)
    val output = ByteArrayOutputStream(minOf(maxBodyBytes, STREAM_BUFFER_BYTES))
    var totalRead = 0

    while (true) {
        val read = bodyChannel.readAvailable(buffer, 0, buffer.size)
        if (read == -1) {
            break
        }
        totalRead += read
        if (totalRead > maxBodyBytes) {
            requestRejected("request body too large", HttpStatusCode.PayloadTooLarge)
        }
        digest.update(buffer, 0, read)
        output.write(buffer, 0, read)
    }

    val bytes = output.toByteArray()
    val hexDigest = digest.digest().joinToString("") { byte -> "%02x".format(byte) }

    return StreamingBodyResult(
        body = bytes.toString(Charsets.UTF_8),
        sha256Hex = hexDigest,
    )
}
