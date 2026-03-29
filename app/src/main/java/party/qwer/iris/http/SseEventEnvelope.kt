package party.qwer.iris.http

data class SseEventEnvelope(
    val id: Long,
    val eventType: String,
    val payload: String,
    val createdAtMs: Long,
)

internal fun initialSseFrames(replay: List<SseEventEnvelope>): String =
    buildString {
        append(": connected\n\n")
        for (event in replay) {
            append("id: ")
            append(event.id)
            append("\ndata: ")
            append(event.payload)
            append("\n\n")
        }
    }
