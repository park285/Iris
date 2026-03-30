package party.qwer.iris.snapshot

import party.qwer.iris.RoomSnapshotData
import party.qwer.iris.model.RoomEvent

interface RoomDiffEngine {
    fun diff(
        prev: RoomSnapshotData,
        curr: RoomSnapshotData,
    ): List<RoomEvent>

    fun diffMissing(prev: RoomSnapshotData): List<RoomEvent>

    fun diffRestored(curr: RoomSnapshotData): List<RoomEvent>
}
