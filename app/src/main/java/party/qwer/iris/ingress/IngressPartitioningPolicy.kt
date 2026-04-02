package party.qwer.iris.ingress

private const val DEFAULT_DISPATCH_PARTITION_COUNT = 4
private const val DEFAULT_PARTITION_QUEUE_CAPACITY = 64

data class IngressPartitioningPolicy(
    val partitionCount: Int = DEFAULT_DISPATCH_PARTITION_COUNT,
    val partitionQueueCapacity: Int = DEFAULT_PARTITION_QUEUE_CAPACITY,
    val maxBufferedDispatches: Int = partitionCount * partitionQueueCapacity,
) {
    init {
        require(partitionCount > 0) { "partitionCount must be positive" }
        require(partitionQueueCapacity > 0) { "partitionQueueCapacity must be positive" }
        require(maxBufferedDispatches > 0) { "maxBufferedDispatches must be positive" }
    }
}

@Deprecated("Use IngressPartitioningPolicy", ReplaceWith("IngressPartitioningPolicy"))
typealias CommandIngressDispatchConfig = IngressPartitioningPolicy
