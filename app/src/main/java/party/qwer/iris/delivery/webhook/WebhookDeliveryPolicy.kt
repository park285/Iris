package party.qwer.iris.delivery.webhook

internal data class WebhookDeliveryPolicy(
    val maxDeliveryAttempts: Int = DEFAULT_MAX_DELIVERY_ATTEMPTS,
    val maxClaimBatchSize: Int = 64,
    val pollIntervalMs: Long = 200L,
    val partitionQueueCapacity: Int = 64,
    val claimRecoveryIntervalMs: Long = 30_000L,
    val claimExpirationMs: Long = 60_000L,
    val deliveryTimeoutMs: Long = 20_000L,
    val claimHeartbeatIntervalMs: Long = 10_000L,
) {
    companion object {
        const val DEFAULT_MAX_DELIVERY_ATTEMPTS = 6
        private const val CLAIM_TIMEOUT_SAFETY_MARGIN_MS = 1_000L
    }

    init {
        require(maxDeliveryAttempts > 0) { "maxDeliveryAttempts must be positive" }
        require(maxClaimBatchSize > 0) { "maxClaimBatchSize must be positive" }
        require(pollIntervalMs > 0L) { "pollIntervalMs must be positive" }
        require(partitionQueueCapacity > 0) { "partitionQueueCapacity must be positive" }
        require(claimRecoveryIntervalMs > 0L) { "claimRecoveryIntervalMs must be positive" }
        require(claimExpirationMs > 0L) { "claimExpirationMs must be positive" }
        require(deliveryTimeoutMs > 0L) { "deliveryTimeoutMs must be positive" }
        require(claimHeartbeatIntervalMs > 0L) { "claimHeartbeatIntervalMs must be positive" }
        require(claimRecoveryIntervalMs <= claimExpirationMs) {
            "claimRecoveryIntervalMs must not exceed claimExpirationMs"
        }
        require(claimHeartbeatIntervalMs < claimExpirationMs) {
            "claimHeartbeatIntervalMs must be less than claimExpirationMs"
        }
        require(claimExpirationMs > deliveryTimeoutMs + CLAIM_TIMEOUT_SAFETY_MARGIN_MS) {
            "claimExpirationMs must exceed deliveryTimeoutMs with safety margin"
        }
    }
}
