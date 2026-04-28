package party.qwer.iris.nativecore

import kotlinx.serialization.Serializable

internal data class NativeDecryptBatchItem(
    val encType: Int,
    val ciphertext: String,
    val userId: Long,
)

@Serializable
internal data class DecryptBatchRequest(
    val items: List<DecryptBatchItem>,
)

@Serializable
internal data class DecryptBatchItem(
    val encType: Int,
    val ciphertext: String,
    val userId: Long,
)

@Serializable
internal data class DecryptBatchResponse(
    val items: List<DecryptBatchResult>,
)

@Serializable
internal data class DecryptBatchResult(
    val ok: Boolean,
    val plaintext: String? = null,
    val errorKind: String? = null,
    val recoverable: Boolean? = null,
    val error: String? = null,
)
