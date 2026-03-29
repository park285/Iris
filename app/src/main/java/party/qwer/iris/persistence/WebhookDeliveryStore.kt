package party.qwer.iris.persistence

import java.io.Closeable

data class PendingWebhookDelivery(
    val messageId: String,
    val roomId: Long,
    val route: String,
    val payloadJson: String,
)

data class ClaimedDelivery(
    val id: Long,
    val messageId: String,
    val roomId: Long,
    val route: String,
    val payloadJson: String,
    val attemptCount: Int,
    val claimToken: String,
)

interface WebhookDeliveryStore : Closeable {
    fun enqueue(delivery: PendingWebhookDelivery): Long

    fun claimReady(limit: Int): List<ClaimedDelivery>

    fun markSent(
        id: Long,
        claimToken: String,
    )

    fun markRetry(
        id: Long,
        claimToken: String,
        nextAttemptAt: Long,
        reason: String?,
    )

    fun markDead(
        id: Long,
        claimToken: String,
        reason: String?,
    )

    fun recoverExpiredClaims(olderThanMs: Long): Int
}
