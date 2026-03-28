package party.qwer.iris

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.encodeURLParameter
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable

private const val DEFAULT_BODY_READ_BUFFER_BYTES = 8 * 1024

internal data class RequestBodyReadResult(
    val body: String,
    val sha256Hex: String,
)

internal suspend fun readProtectedRequestBody(
    call: ApplicationCall,
    maxBodyBytes: Int,
): RequestBodyReadResult =
    readRequestBodyWithinLimit(
        bodyChannel = call.receiveChannel(),
        declaredContentLength = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull(),
        maxBodyBytes = maxBodyBytes,
    )

internal suspend fun readRequestBodyWithinLimit(
    bodyChannel: ByteReadChannel,
    declaredContentLength: Long?,
    maxBodyBytes: Int,
): RequestBodyReadResult {
    if (declaredContentLength != null && declaredContentLength > maxBodyBytes) {
        requestRejected("request body too large", HttpStatusCode.PayloadTooLarge)
    }

    val buffer = ByteArray(DEFAULT_BODY_READ_BUFFER_BYTES)
    val output = java.io.ByteArrayOutputStream(minOf(maxBodyBytes, DEFAULT_BODY_READ_BUFFER_BYTES))
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
        output.write(buffer, 0, read)
    }
    val bytes = output.toByteArray()
    return RequestBodyReadResult(
        body = bytes.toString(Charsets.UTF_8),
        sha256Hex = sha256Hex(bytes),
    )
}

internal fun canonicalRequestTarget(
    path: String,
    queryParameters: Parameters,
): String {
    val encodedQuery =
        queryParameters
            .entries()
            .asSequence()
            .flatMap { (name, values) ->
                if (values.isEmpty()) {
                    sequenceOf(name.encodeURLParameter() to null)
                } else {
                    values.asSequence().map { value ->
                        name.encodeURLParameter() to value.encodeURLParameter()
                    }
                }
            }.sortedWith(compareBy({ it.first }, { it.second.orEmpty() }))
            .joinToString("&") { (name, value) ->
                if (value == null) {
                    name
                } else {
                    "$name=$value"
                }
            }
    return if (encodedQuery.isBlank()) path else "$path?$encodedQuery"
}
