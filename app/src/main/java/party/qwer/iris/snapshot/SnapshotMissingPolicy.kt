package party.qwer.iris.snapshot

internal data class SnapshotMissingPolicy(
    val confirmAfterConsecutiveMisses: Int = 2,
    val confirmAfterMs: Long = 30_000L,
    val restoreQuietlyWhenPending: Boolean = true,
) {
    init {
        require(confirmAfterConsecutiveMisses > 0) { "confirmAfterConsecutiveMisses must be positive" }
        require(confirmAfterMs >= 0L) { "confirmAfterMs must be non-negative" }
    }
}

internal data class SnapshotCoordinatorDebugSnapshot(
    val dirtyRoomCount: Int,
    val cachedRoomCount: Int,
    val pendingFullReconcile: Boolean,
    val pendingPruneCutoffEpochMs: Long?,
)
