package party.qwer.iris.snapshot

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import party.qwer.iris.RoomSnapshotData
import java.util.concurrent.atomic.AtomicInteger

class SnapshotCoordinator(
    scope: CoroutineScope,
    private val roomSnapshotReader: RoomSnapshotReader,
    private val diffEngine: RoomDiffEngine,
    private val emitter: SnapshotEventEmitter,
) {
    private val commands = Channel<SnapshotCommand>(Channel.UNLIMITED)

    private val previousSnapshots = mutableMapOf<Long, RoomSnapshotData>()
    private val dirtyRoomSet = mutableSetOf<Long>()
    private val dirtyRoomQueue = ArrayDeque<Long>()
    private val dirtyRoomCountValue = AtomicInteger(0)

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
            .filter { it > 0L }
            .forEach { chatId ->
                previousSnapshots[chatId] = roomSnapshotReader.snapshot(chatId)
            }
    }

    private fun handleMarkDirty(chatId: Long) {
        if (chatId <= 0L) return
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
        }
    }

    private fun handleFullReconcile() {
        previousSnapshots.keys.forEach { chatId ->
            handleMarkDirty(chatId)
        }
        dirtyRoomCountValue.set(dirtyRoomSet.size)
    }
}
