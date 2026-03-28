package party.qwer.iris.model

import kotlinx.serialization.Serializable

@Serializable
data class ReplyStatusSnapshot(
    val requestId: String,
    val state: ReplyLifecycleState,
    val updatedAtEpochMs: Long,
    val detail: String? = null,
)
