package party.qwer.iris.http

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.path
import io.ktor.server.response.respond
import party.qwer.iris.ActiveSecretProvider
import party.qwer.iris.AuthResult
import party.qwer.iris.IrisLogger
import party.qwer.iris.RequestAuthenticator
import party.qwer.iris.SignaturePrecheck
import party.qwer.iris.canonicalRequestTarget
import party.qwer.iris.model.CommonErrorResponse

internal class AuthSupport(
    private val authenticator: RequestAuthenticator,
    private val config: ActiveSecretProvider,
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

    suspend fun precheckInboundSignature(
        call: ApplicationCall,
        method: String,
    ): SignaturePrecheck? = precheckSignature(call, SecretRole.INBOUND, method)

    suspend fun precheckBotControlSignature(
        call: ApplicationCall,
        method: String,
    ): SignaturePrecheck? = precheckSignature(call, SecretRole.BOT_CONTROL, method)

    suspend fun finalizeSignature(
        call: ApplicationCall,
        precheck: SignaturePrecheck,
        actualBodySha256Hex: String,
    ): Boolean =
        when (finalizeRequestAuthorization(precheck, actualBodySha256Hex)) {
            AuthResult.AUTHORIZED -> true
            AuthResult.SERVICE_UNAVAILABLE -> {
                call.respond(HttpStatusCode.ServiceUnavailable, CommonErrorResponse(message = "service unavailable"))
                false
            }
            AuthResult.UNAUTHORIZED -> {
                call.respond(HttpStatusCode.Unauthorized, CommonErrorResponse(message = "unauthorized"))
                false
            }
        }

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

    private suspend fun precheckSignature(
        call: ApplicationCall,
        role: SecretRole,
        method: String,
    ): SignaturePrecheck? {
        val result =
            precheckRequest(
                role = role,
                method = method,
                path = canonicalRequestTarget(call.request.path(), call.request.queryParameters),
                timestampHeader = call.request.headers["X-Iris-Timestamp"],
                nonceHeader = call.request.headers["X-Iris-Nonce"],
                signatureHeader = call.request.headers["X-Iris-Signature"],
                declaredBodySha256HexHeader = call.request.headers["X-Iris-Body-Sha256"],
            )
        return when (result.result) {
            AuthResult.AUTHORIZED -> checkNotNull(result.precheck)
            AuthResult.SERVICE_UNAVAILABLE -> {
                IrisLogger.error("[AuthSupport] Refusing protected request because ${role.description} is not configured")
                call.respond(HttpStatusCode.ServiceUnavailable, CommonErrorResponse(message = "service unavailable"))
                null
            }
            AuthResult.UNAUTHORIZED -> {
                call.respond(HttpStatusCode.Unauthorized, CommonErrorResponse(message = "unauthorized"))
                null
            }
        }
    }

    fun precheckRequest(
        role: SecretRole,
        method: String,
        path: String,
        timestampHeader: String?,
        nonceHeader: String?,
        signatureHeader: String?,
        declaredBodySha256HexHeader: String?,
    ) = authenticator.preverify(
        method = method,
        path = path,
        declaredBodySha256Hex = declaredBodySha256HexHeader,
        expectedSecret = role.resolveSecret(config),
        timestampHeader = timestampHeader,
        nonceHeader = nonceHeader,
        signatureHeader = signatureHeader,
    )

    fun preauthenticateRequest(
        role: SecretRole,
        method: String,
        path: String,
        timestampHeader: String?,
        nonceHeader: String?,
        signatureHeader: String?,
        declaredBodySha256HexHeader: String?,
    ) = precheckRequest(
        role = role,
        method = method,
        path = path,
        timestampHeader = timestampHeader,
        nonceHeader = nonceHeader,
        signatureHeader = signatureHeader,
        declaredBodySha256HexHeader = declaredBodySha256HexHeader,
    )

    fun finalizeRequestAuthorization(
        precheck: SignaturePrecheck,
        actualBodySha256Hex: String,
    ): AuthResult = authenticator.finalizeAuthorized(precheck, actualBodySha256Hex)

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
        private val secretSelector: (ActiveSecretProvider) -> String,
    ) {
        INBOUND(
            description = "inbound signing secret",
            secretSelector = ActiveSecretProvider::activeInboundSigningSecret,
        ),
        BOT_CONTROL(
            description = "bot control token",
            secretSelector = ActiveSecretProvider::activeBotControlToken,
        ),
        ;

        fun resolveSecret(config: ActiveSecretProvider): String = secretSelector(config)
    }
}
