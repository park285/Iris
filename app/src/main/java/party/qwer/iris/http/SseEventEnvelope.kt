package party.qwer.iris.http

data class SseEventEnvelope(
    val id: Long,
    val eventType: String,
    val payload: String,
    val createdAtMs: Long,
)

internal fun formatSseFrame(event: SseEventEnvelope): String =
    buildString {
        append("id: ").append(event.id).append('\n')
        append("event: ").append(sanitizeSseField(event.eventType)).append('\n')

        event.payload
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .split('\n')
            .forEach { line ->
                append("data: ").append(line).append('\n')
            }

        append('\n')
    }

internal fun initialSseFrames(replay: List<SseEventEnvelope>): String =
    buildString {
        append(": connected\n\n")
        for (event in replay) {
            append(formatSseFrame(event))
        }
    }

private fun sanitizeSseField(rawValue: String): String =
    rawValue
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .replace('\n', ' ')
