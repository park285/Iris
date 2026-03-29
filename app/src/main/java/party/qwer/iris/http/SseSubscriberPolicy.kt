package party.qwer.iris.http

internal data class SseSubscriberPolicy(
    val bufferCapacity: Int = 256,
    val slowSubscriberTimeoutMs: Long = 30_000,
    val replayWindowSize: Int = 64,
) {
    init {
        require(bufferCapacity > 0) { "bufferCapacity must be positive" }
        require(slowSubscriberTimeoutMs > 0) { "slowSubscriberTimeoutMs must be positive" }
        require(replayWindowSize in 1..bufferCapacity) {
            "replayWindowSize must be between 1 and bufferCapacity"
        }
    }
}
