package party.qwer.iris.reply

import party.qwer.iris.ReplyQueueKey

internal enum class ReplyAdmissionLifecycle {
    STOPPED,
    RUNNING,
    TERMINATED,
}

internal data class ReplyAdmissionDebugSnapshot(
    val lifecycle: ReplyAdmissionLifecycle,
    val activeWorkers: Int,
    val closingWorkers: Int,
    val workers: List<ReplyAdmissionWorkerDebug>,
)

internal data class ReplyAdmissionWorkerDebug(
    val key: ReplyQueueKey,
    val workerId: Long,
    val ageMs: Long,
    val queueDepth: Int,
    val mailboxState: String,
)
