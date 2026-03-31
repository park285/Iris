package party.qwer.iris.http

import io.ktor.server.application.ApplicationCall
import party.qwer.iris.SignaturePrecheck

internal typealias ProtectedBodyReader = suspend (ApplicationCall, Int) -> RequestBodyHandle

internal suspend fun withVerifiedProtectedBody(
    call: ApplicationCall,
    maxBodyBytes: Int,
    bodyReader: ProtectedBodyReader = ::readProtectedBody,
    precheck: suspend () -> SignaturePrecheck?,
    finalize: suspend (SignaturePrecheck, String) -> Boolean,
    block: suspend (RequestBodyHandle) -> Unit,
): Boolean {
    val signaturePrecheck = precheck() ?: return false
    bodyReader(call, maxBodyBytes).use { rawBody ->
        if (!finalize(signaturePrecheck, rawBody.sha256Hex)) {
            return false
        }
        block(rawBody)
    }
    return true
}
