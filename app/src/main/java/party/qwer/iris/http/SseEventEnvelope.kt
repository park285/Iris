package party.qwer.iris.http

internal data class SseEventEnvelope(
    val id: Long,
    val eventType: String,
    val payload: String,
    val createdAtMs: Long,
)
