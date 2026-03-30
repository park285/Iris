package party.qwer.iris.snapshot

import party.qwer.iris.storage.ChatId

sealed interface SnapshotCommand {
    data class MarkDirty(
        val chatId: ChatId,
    ) : SnapshotCommand

    data class Drain(
        val budget: Int,
    ) : SnapshotCommand

    data object FullReconcile : SnapshotCommand

    data object SeedCache : SnapshotCommand
}
