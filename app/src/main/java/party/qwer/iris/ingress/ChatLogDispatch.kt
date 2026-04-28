package party.qwer.iris.ingress

import party.qwer.iris.KakaoDB
import party.qwer.iris.ParsedCommand

internal data class ChatLogDispatch(
    val logEntry: KakaoDB.ChatLogEntry,
    val decryptedMessage: String? = null,
    val decryptedAttachment: String? = null,
    val parsedCommand: ParsedCommand? = null,
) {
    val logId: Long
        get() = logEntry.id
}

internal enum class DispatchOutcome {
    COMPLETED,
    RETRY_LATER,
}
