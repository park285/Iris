package party.qwer.iris.delivery.webhook

internal data class WebhookDeliveryPolicy(
    val maxDeliveryAttempts: Int = 6,
    val maxClaimBatchSize: Int = 64,
    val pollIntervalMs: Long = 200L,
    val partitionQueueCapacity: Int = 64,
    val claimRecoveryIntervalMs: Long = 30_000L,
    val claimExpirationMs: Long = 60_000L,
) {
    init {
        require(maxDeliveryAttempts > 0) { "maxDeliveryAttempts must be positive" }
        require(maxClaimBatchSize > 0) { "maxClaimBatchSize must be positive" }
        require(pollIntervalMs > 0L) { "pollIntervalMs must be positive" }
        require(partitionQueueCapacity > 0) { "partitionQueueCapacity must be positive" }
        require(claimRecoveryIntervalMs > 0L) { "claimRecoveryIntervalMs must be positive" }
        require(claimExpirationMs > 0L) { "claimExpirationMs must be positive" }
    }
}
