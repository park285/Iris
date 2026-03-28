package party.qwer.iris.model

import kotlinx.serialization.Serializable

@Serializable
internal enum class WebhookOutboxStatus {
    PENDING,
    SENDING,
    RETRY,
    SENT,
    DEAD,
}

@Serializable
internal data class StoredWebhookOutboxEntry(
    val id: Long,
    val roomId: Long,
    val route: String,
    val messageId: String,
    val payloadJson: String,
    val attemptCount: Int,
    val nextAttemptAt: Long,
    val status: WebhookOutboxStatus,
    val lastError: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
internal data class WebhookOutboxFileState(
    val nextId: Long = 1L,
    val entries: List<StoredWebhookOutboxEntry> = emptyList(),
)
