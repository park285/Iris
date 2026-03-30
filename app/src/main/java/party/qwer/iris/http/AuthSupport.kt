package party.qwer.iris.http

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.path
import io.ktor.server.response.respond
import party.qwer.iris.AuthResult
import party.qwer.iris.ConfigProvider
import party.qwer.iris.IrisLogger
import party.qwer.iris.RequestAuthenticator
import party.qwer.iris.canonicalRequestTarget
import party.qwer.iris.model.CommonErrorResponse

internal class AuthSupport(
    private val authenticator: RequestAuthenticator,
    private val config: ConfigProvider,
) {
    suspend fun requireBotToken(
        call: ApplicationCall,
        method: String,
        bodySha256Hex: String = party.qwer.iris.sha256Hex(ByteArray(0)),
    ): Boolean {
        val result =
            authenticateRequest(
                method = method,
                path = canonicalRequestTarget(call.request.path(), call.request.queryParameters),
                bodySha256Hex = bodySha256Hex,
                timestampHeader = call.request.headers["X-Iris-Timestamp"],
                nonceHeader = call.request.headers["X-Iris-Nonce"],
                signatureHeader = call.request.headers["X-Iris-Signature"],
            )
        return when (result) {
            AuthResult.AUTHORIZED -> true
            AuthResult.SERVICE_UNAVAILABLE -> {
                IrisLogger.error("[AuthSupport] Refusing protected request because bot token is not configured")
                call.respond(HttpStatusCode.ServiceUnavailable, CommonErrorResponse(message = "service unavailable"))
                false
            }
            AuthResult.UNAUTHORIZED -> {
                call.respond(HttpStatusCode.Unauthorized, CommonErrorResponse(message = "unauthorized"))
                false
            }
        }
    }

    fun authenticateRequest(
        method: String,
        path: String,
        bodySha256Hex: String,
        timestampHeader: String?,
        nonceHeader: String?,
        signatureHeader: String?,
    ): AuthResult =
        authenticator.authenticate(
            method = method,
            path = path,
            body = "",
            bodySha256Hex = bodySha256Hex,
            expectedSecret = config.inboundSigningSecret,
            timestampHeader = timestampHeader,
            nonceHeader = nonceHeader,
            signatureHeader = signatureHeader,
        )
}
