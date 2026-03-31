package party.qwer.iris.http

import party.qwer.iris.VerifiedImageHandleStagingPolicy
import party.qwer.iris.VerifiedImagePayloadHandle
import party.qwer.iris.imageFormatFromContentType
import party.qwer.iris.verifyImagePayloadHandles
import java.util.Base64

internal data class Base64ImagePayload(
    val base64: String,
    val contentType: String? = null,
)

internal class Base64ImageIngressService(
    private val decoder: Base64.Decoder = Base64.getDecoder(),
) {
    fun decodeToVerifiedHandles(
        payloads: List<Base64ImagePayload>,
        replyImageIngressPolicy: ReplyImageIngressPolicy,
    ): List<VerifiedImagePayloadHandle> {
        val imagePolicy = replyImageIngressPolicy.imagePolicy
        val stagingPolicy =
            VerifiedImageHandleStagingPolicy(
                maxInMemoryBytes = replyImageIngressPolicy.bufferingPolicy.maxInMemoryBytes,
                spillDirectory = replyImageIngressPolicy.bufferingPolicy.spillDirectory,
            )
        val decodedBytes =
            payloads.map { payload ->
                require(payload.base64.isNotBlank()) { "empty image data" }
                runCatching {
                    decoder.decode(payload.base64)
                }.getOrElse {
                    throw IllegalArgumentException("invalid base64 image payload")
                }
            }
        val handles =
            try {
                verifyImagePayloadHandles(
                    imageBytesList = decodedBytes,
                    policy = imagePolicy,
                    stagingPolicy = stagingPolicy,
                )
            } catch (error: Exception) {
                throw IllegalArgumentException(error.message ?: "invalid image payload", error)
            }

        return try {
            handles.zip(payloads).forEach { (handle, payload) ->
                val declaredContentType =
                    payload.contentType
                        ?.trim()
                        ?.lowercase()
                        ?.takeIf { it.isNotEmpty() }
                        ?: return@forEach
                require(declaredContentType in imagePolicy.allowedContentTypes) { "image contentType is not allowed" }
                require(handle.format == imageFormatFromContentType(declaredContentType)) { "mismatched image format" }
            }
            handles
        } catch (error: Exception) {
            handles.forEach { handle -> runCatching { handle.close() } }
            throw error
        }
    }
}
