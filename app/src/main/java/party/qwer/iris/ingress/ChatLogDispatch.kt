package party.qwer.iris.ingress

import party.qwer.iris.KakaoDB

internal data class ChatLogDispatch(
    val logEntry: KakaoDB.ChatLogEntry,
) {
    val logId: Long
        get() = logEntry.id
}

internal enum class DispatchOutcome {
    COMPLETED,
    RETRY_LATER,
}
