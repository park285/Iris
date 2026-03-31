package party.qwer.iris.snapshot

import kotlinx.coroutines.CompletableDeferred
import party.qwer.iris.storage.ChatId

internal sealed interface SnapshotCommand {
    data class MarkDirty(
        val chatId: ChatId,
    ) : SnapshotCommand

    data class PruneMissing(
        val cutoffEpochMs: Long,
    ) : SnapshotCommand

    data class Drain(
        val budget: Int,
    ) : SnapshotCommand

    data class GetDebugSnapshot(
        val replyTo: CompletableDeferred<SnapshotCoordinatorDebugSnapshot>,
    ) : SnapshotCommand

    data object FullReconcile : SnapshotCommand

    data object SeedCache : SnapshotCommand
}
