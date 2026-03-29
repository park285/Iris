package party.qwer.iris.snapshot

import party.qwer.iris.RoomSnapshotData

fun interface RoomDiffEngine {
    fun diff(
        prev: RoomSnapshotData,
        curr: RoomSnapshotData,
    ): List<Any>
}
