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
    suspend fun requireInboundSignature(
        call: ApplicationCall,
        method: String,
        bodySha256Hex: String = party.qwer.iris.sha256Hex(ByteArray(0)),
    ): Boolean = requireSignature(call, SecretRole.INBOUND, method, bodySha256Hex)

    suspend fun requireBotControlSignature(
        call: ApplicationCall,
        method: String,
        bodySha256Hex: String = party.qwer.iris.sha256Hex(ByteArray(0)),
    ): Boolean = requireSignature(call, SecretRole.BOT_CONTROL, method, bodySha256Hex)

    private suspend fun requireSignature(
        call: ApplicationCall,
        role: SecretRole,
        method: String,
        bodySha256Hex: String = party.qwer.iris.sha256Hex(ByteArray(0)),
    ): Boolean {
        val result =
            authenticateRequest(
                role = role,
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
                IrisLogger.error("[AuthSupport] Refusing protected request because ${role.description} is not configured")
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
        role: SecretRole,
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
            expectedSecret = role.resolveSecret(config),
            timestampHeader = timestampHeader,
            nonceHeader = nonceHeader,
            signatureHeader = signatureHeader,
        )

    internal enum class SecretRole(
        val description: String,
        private val secretSelector: (ConfigProvider) -> String,
    ) {
        INBOUND(
            description = "inbound signing secret",
            secretSelector = ConfigProvider::inboundSigningSecret,
        ),
        BOT_CONTROL(
            description = "bot control token",
            secretSelector = ConfigProvider::botControlToken,
        ),
        ;

        fun resolveSecret(config: ConfigProvider): String = secretSelector(config)
    }
}
