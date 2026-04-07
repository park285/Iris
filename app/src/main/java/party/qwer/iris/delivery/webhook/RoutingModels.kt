package party.qwer.iris.delivery.webhook

import kotlinx.serialization.json.JsonElement
import java.security.MessageDigest

data class RoutingCommand(
    val text: String,
    val room: String,
    val sender: String,
    val userId: String,
    val sourceLogId: Long,
    val chatLogId: String? = null,
    val roomType: String? = null,
    val roomLinkId: String? = null,
    val threadId: String? = null,
    val threadScope: Int? = null,
    val messageType: String? = null,
    val attachment: String? = null,
    val eventPayload: JsonElement? = null,
    val senderRole: Int? = null,
)

enum class RoutingResult {
    ACCEPTED,
    SKIPPED,
    RETRY_LATER,
}

internal fun buildRoutingMessageId(
    command: RoutingCommand,
    route: String,
): String {
    if (command.sourceLogId > 0L) {
        return "kakao-log-${command.sourceLogId}-$route"
    }

    val fingerprintSource =
        buildString {
            append(route)
            append('|')
            append(command.room)
            append('|')
            append(command.userId)
            append('|')
            append(command.messageType.orEmpty())
            append('|')
            append(command.text)
            append('|')
            append(command.chatLogId.orEmpty())
            append('|')
            append(command.attachment.orEmpty())
            append('|')
            append(command.eventPayload?.toString().orEmpty())
        }
    return "kakao-system-${sha256Hex(fingerprintSource)}-$route"
}

private fun sha256Hex(input: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
    return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
}
