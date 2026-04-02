package party.qwer.iris.http

import io.ktor.http.HttpStatusCode
import io.ktor.http.content.MultiPartData
import io.ktor.http.content.PartData
import io.ktor.server.application.ApplicationCall
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import party.qwer.iris.SignaturePrecheck
import party.qwer.iris.VerifiedImagePayloadHandle
import party.qwer.iris.invalidRequest
import party.qwer.iris.model.ReplyImageMetadata
import party.qwer.iris.requestRejected
import party.qwer.iris.sha256Hex

internal class MultipartReplyPayload(
    val metadata: ReplyImageMetadata,
    val target: ValidatedReplyTarget,
    private val handles: MutableList<VerifiedImagePayloadHandle>,
) : AutoCloseable {
    private var transferred = false

    fun takeImageHandles(): List<VerifiedImagePayloadHandle> {
        check(!transferred) { "image handles already transferred" }
        transferred = true
        val out = handles.toList()
        handles.clear()
        return out
    }

    override fun close() {
        handles.forEach { handle ->
            runCatching { handle.close() }
        }
        handles.clear()
    }
}

internal class MultipartReplyCollector(
    private val call: ApplicationCall,
    private val authSupport: AuthSupport,
    private val serverJson: Json,
    private val precheck: SignaturePrecheck,
    private val replyImageIngressPolicy: ReplyImageIngressPolicy,
    private val imagePartStager: MultipartImagePartStager,
) {
    private val imagePolicy = replyImageIngressPolicy.imagePolicy
    private val imageHandles = mutableListOf<VerifiedImagePayloadHandle>()
    private var metadata: ReplyImageMetadata? = null
    private var nextExpectedImageIndex = 0
    private var totalImageBytes = 0L

    suspend fun collect(multipart: MultiPartData): MultipartReplyPayload? {
        while (true) {
            val part = multipart.readPart() ?: break
            try {
                when (part) {
                    is PartData.FormItem -> {
                        if (!acceptMetadata(part, multipart)) {
                            return null
                        }
                    }

                    is PartData.FileItem -> acceptImage(part)
                    else -> Unit
                }
            } finally {
                part.dispose()
            }
        }

        val currentMetadata = metadata ?: invalidRequest("missing metadata part")
        if (imageHandles.size != currentMetadata.images.size) {
            invalidRequest("missing image part")
        }

        val target = validateReplyTarget(currentMetadata)
        val handlesForPayload = imageHandles.toMutableList()
        imageHandles.clear()

        return MultipartReplyPayload(
            metadata = currentMetadata,
            target = target,
            handles = handlesForPayload,
        )
    }

    fun closeUntransferredHandles() {
        imageHandles.forEach { handle ->
            runCatching { handle.close() }
        }
        imageHandles.clear()
    }

    private suspend fun acceptMetadata(
        part: PartData.FormItem,
        multipart: MultiPartData,
    ): Boolean {
        if (part.name != "metadata") {
            return true
        }
        if (metadata != null) {
            invalidRequest("duplicate metadata part")
        }
        val rawMetadata = part.value.toByteArray(Charsets.UTF_8)
        if (rawMetadata.size > imagePolicy.maxMetadataBytes) {
            requestRejected("metadata part too large", HttpStatusCode.PayloadTooLarge)
        }
        if (!authSupport.finalizeSignature(call, precheck, sha256Hex(rawMetadata))) {
            discardRemainingMultipartParts(multipart)
            return false
        }
        metadata = decodeReplyImageMetadata(serverJson, rawMetadata)
        validateMultipartImageManifest(checkNotNull(metadata), replyImageIngressPolicy)
        return true
    }

    private fun acceptImage(part: PartData.FileItem) {
        if (part.name != "image") {
            return
        }
        val currentMetadata = metadata ?: invalidRequest("metadata part must precede image parts")
        val expectedPart =
            currentMetadata.images.getOrNull(nextExpectedImageIndex)
                ?: invalidRequest("unexpected extra image part")
        validateExpectedPartIndex(expectedPart, nextExpectedImageIndex)
        val handle = imagePartStager.stage(part, expectedPart, replyImageIngressPolicy)
        totalImageBytes =
            accumulateReplyBodyBytes(
                current = totalImageBytes,
                partBytes = handle.sizeBytes,
                maxBytes = imagePolicy.maxTotalBytes.toLong(),
            )
        imageHandles += handle
        nextExpectedImageIndex += 1
    }
}

private fun decodeReplyImageMetadata(
    serverJson: Json,
    rawMetadata: ByteArray,
): ReplyImageMetadata =
    try {
        serverJson.decodeFromString(ReplyImageMetadata.serializer(), rawMetadata.decodeToString())
    } catch (_: SerializationException) {
        invalidRequest("invalid metadata part")
    }

private suspend fun discardRemainingMultipartParts(multipart: MultiPartData) {
    while (true) {
        val remainingPart = multipart.readPart() ?: break
        remainingPart.dispose()
    }
}
