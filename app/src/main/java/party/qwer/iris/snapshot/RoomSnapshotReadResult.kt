package party.qwer.iris.snapshot

import party.qwer.iris.RoomSnapshotData

sealed interface RoomSnapshotReadResult {
    data class Present(
        val snapshot: RoomSnapshotData,
    ) : RoomSnapshotReadResult

    data object Missing : RoomSnapshotReadResult
}
