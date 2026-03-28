package party.qwer.iris

import io.ktor.http.HttpStatusCode

internal class ApiRequestException(
    override val message: String,
    val status: HttpStatusCode = HttpStatusCode.BadRequest,
) : RuntimeException(message)

internal fun invalidRequest(message: String): Nothing = throw ApiRequestException(message)

internal fun internalServerFailure(message: String): Nothing = throw ApiRequestException(message, HttpStatusCode.InternalServerError)

internal fun requestRejected(
    message: String,
    status: HttpStatusCode,
): Nothing = throw ApiRequestException(message, status)

internal fun mapApiErrorCode(status: HttpStatusCode): String =
    when (status) {
        HttpStatusCode.BadRequest -> "INVALID_REQUEST"
        HttpStatusCode.NotFound -> "NOT_FOUND"
        HttpStatusCode.Unauthorized -> "UNAUTHORIZED"
        HttpStatusCode.Forbidden -> "FORBIDDEN"
        else -> "INTERNAL_ERROR"
    }
