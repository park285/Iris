package party.qwer.iris.snapshot

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import party.qwer.iris.RoomSnapshotData
import party.qwer.iris.storage.ChatId
import java.util.concurrent.atomic.AtomicInteger

class SnapshotCoordinator(
    scope: CoroutineScope,
    private val roomSnapshotReader: RoomSnapshotReader,
    private val diffEngine: RoomDiffEngine,
    private val emitter: SnapshotEventEmitter,
) {
    private val commands = Channel<SnapshotCommand>(Channel.UNLIMITED)

    private val previousSnapshots = mutableMapOf<ChatId, RoomSnapshotData>()
    private val dirtyRoomSet = mutableSetOf<ChatId>()
    private val dirtyRoomQueue = ArrayDeque<ChatId>()
    private val dirtyRoomCountValue = AtomicInteger(0)
    private val deletedRoomsPendingCleanup = mutableSetOf<ChatId>()
    // 삭제 완료된 방 — 빈 베이스라인이 previousSnapshots에 유지됨
    private val cleanedUpRooms = mutableSetOf<ChatId>()

    init {
        scope.launch {
            for (cmd in commands) {
                handleInternal(cmd)
            }
        }
    }

    suspend fun send(command: SnapshotCommand) {
        commands.send(command)
    }

    fun enqueue(command: SnapshotCommand) {
        commands.trySend(command)
    }

    fun dirtyRoomCount(): Int = dirtyRoomCountValue.get()

    private fun handleInternal(cmd: SnapshotCommand) {
        when (cmd) {
            is SnapshotCommand.SeedCache -> handleSeedCache()
            is SnapshotCommand.MarkDirty -> handleMarkDirty(cmd.chatId)
            is SnapshotCommand.Drain -> handleDrain(cmd.budget)
            is SnapshotCommand.FullReconcile -> handleFullReconcile()
        }
    }

    private fun handleSeedCache() {
        roomSnapshotReader
            .listRoomChatIds()
            .filter { it.value > 0L }
            .forEach { chatId ->
                previousSnapshots[chatId] = roomSnapshotReader.snapshot(chatId)
            }
    }

    private fun handleMarkDirty(chatId: ChatId) {
        if (chatId.value <= 0L) return
        if (dirtyRoomSet.add(chatId)) {
            dirtyRoomQueue.addLast(chatId)
            dirtyRoomCountValue.incrementAndGet()
        }
    }

    private fun handleDrain(budget: Int) {
        repeat(budget) {
            val chatId = dirtyRoomQueue.removeFirstOrNull() ?: return
            if (dirtyRoomSet.remove(chatId)) {
                dirtyRoomCountValue.decrementAndGet()
            }

            val currentSnapshot = roomSnapshotReader.snapshot(chatId)
            val previousSnapshot = previousSnapshots.put(chatId, currentSnapshot) ?: return@repeat

            val events = diffEngine.diff(previousSnapshot, currentSnapshot)
            if (events.isNotEmpty()) {
                emitter.emit(events)
            }
            if (chatId in deletedRoomsPendingCleanup && currentSnapshot.isEmptySnapshot()) {
                // 빈 베이스라인을 유지하여 부활 시 join 이벤트 발생
                deletedRoomsPendingCleanup.remove(chatId)
                cleanedUpRooms.add(chatId)
            }
        }
    }

    private fun handleFullReconcile() {
        val currentRoomIds =
            roomSnapshotReader
            .listRoomChatIds()
            .filter { it.value > 0L }
            .toSet()

        // 부활한 방: 이전에 클린업 완료되었으나 다시 나타난 방
        val resurrected = cleanedUpRooms.intersect(currentRoomIds)
        cleanedUpRooms.removeAll(resurrected)

        deletedRoomsPendingCleanup.clear()
        deletedRoomsPendingCleanup.addAll(previousSnapshots.keys - currentRoomIds - cleanedUpRooms)

        // 클린업된 방은 dirty 대상에서 제외 (부활 방은 포함)
        val activeRooms = previousSnapshots.keys - cleanedUpRooms
        (activeRooms + currentRoomIds).forEach(::handleMarkDirty)
    }

    private fun RoomSnapshotData.isEmptySnapshot(): Boolean =
        linkId == null &&
            memberIds.isEmpty() &&
            blindedIds.isEmpty() &&
            nicknames.isEmpty() &&
            roles.isEmpty() &&
            profileImages.isEmpty()
}
