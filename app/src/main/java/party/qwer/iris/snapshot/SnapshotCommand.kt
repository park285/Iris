package party.qwer.iris.snapshot

sealed interface SnapshotCommand {
    data class MarkDirty(val chatId: Long) : SnapshotCommand
    data class Drain(val budget: Int) : SnapshotCommand
    data object FullReconcile : SnapshotCommand
    data object SeedCache : SnapshotCommand
}
