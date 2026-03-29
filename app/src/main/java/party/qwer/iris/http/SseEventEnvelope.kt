package party.qwer.iris.http

data class SseEventEnvelope(
    val id: Long,
    val eventType: String,
    val payload: String,
    val createdAtMs: Long,
)
