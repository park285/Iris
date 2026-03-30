package party.qwer.iris.snapshot

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import party.qwer.iris.RoomSnapshotData
import party.qwer.iris.persistence.InMemorySnapshotStateStore
import party.qwer.iris.persistence.PersistedSnapshotState
import party.qwer.iris.persistence.SnapshotStateStore
import party.qwer.iris.storage.ChatId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal class SnapshotCoordinator(
    scope: CoroutineScope,
    private val roomSnapshotReader: RoomSnapshotReader,
    private val diffEngine: RoomDiffEngine,
    private val emitter: SnapshotEventEmitter,
    private val stateStore: SnapshotStateStore = InMemorySnapshotStateStore(),
) {
    private sealed interface SnapshotState {
        data class Present(
            val snapshot: RoomSnapshotData,
        ) : SnapshotState

        data object Missing : SnapshotState
    }

    private val commandSignals = Channel<Unit>(Channel.CONFLATED)
    private val seedRequested = AtomicBoolean(false)
    private val fullReconcileRequested = AtomicBoolean(false)
    private val pendingDrainBudget = AtomicInteger(0)
    private val pendingDirtyRooms = ConcurrentHashMap.newKeySet<ChatId>()

    private val previousSnapshots = mutableMapOf<ChatId, SnapshotState>()
    private val dirtyRoomSet = mutableSetOf<ChatId>()
    private val dirtyRoomQueue = ArrayDeque<ChatId>()
    private val dirtyRoomCountValue = AtomicInteger(0)

    init {
        scope.launch {
            for (ignored in commandSignals) {
                drainPendingCommands()
            }
        }
    }

    suspend fun send(command: SnapshotCommand) {
        mergePendingCommand(command)
        commandSignals.send(Unit)
    }

    fun enqueue(command: SnapshotCommand) {
        mergePendingCommand(command)
        commandSignals.trySend(Unit)
    }

    fun dirtyRoomCount(): Int = dirtyRoomCountValue.get() + pendingDirtyRooms.size

    private fun mergePendingCommand(command: SnapshotCommand) {
        when (command) {
            SnapshotCommand.SeedCache -> seedRequested.set(true)
            SnapshotCommand.FullReconcile -> fullReconcileRequested.set(true)
            is SnapshotCommand.MarkDirty -> {
                if (command.chatId.value > 0L) {
                    pendingDirtyRooms.add(command.chatId)
                }
            }
            is SnapshotCommand.Drain -> {
                pendingDrainBudget.accumulateAndGet(command.budget.coerceAtLeast(0), ::maxOf)
            }
        }
    }

    private fun drainPendingCommands() {
        while (true) {
            var handled = false

            if (seedRequested.compareAndSet(true, false)) {
                handleSeedCache()
                handled = true
            }

            if (fullReconcileRequested.compareAndSet(true, false)) {
                handleFullReconcile()
                handled = true
            }

            val pendingDirty =
                pendingDirtyRooms
                    .toList()
                    .filter(pendingDirtyRooms::remove)
            if (pendingDirty.isNotEmpty()) {
                pendingDirty.forEach(::handleMarkDirty)
                handled = true
            }

            val drainBudget = pendingDrainBudget.getAndSet(0)
            if (drainBudget > 0) {
                handleDrain(drainBudget)
                handled = true
            }

            if (!handled) {
                return
            }
        }
    }

    private fun handleSeedCache() {
        previousSnapshots.clear()
        stateStore
            .loadAll()
            .forEach { (chatId, state) ->
                previousSnapshots[chatId] = state.toSnapshotState()
            }

        val currentRoomIds =
            roomSnapshotReader
                .listRoomChatIds()
                .filter { it.value > 0L }
                .toSet()

        if (previousSnapshots.isEmpty()) {
            currentRoomIds.forEach { chatId ->
                when (val result = roomSnapshotReader.snapshot(chatId)) {
                    is RoomSnapshotReadResult.Present -> {
                        previousSnapshots[chatId] = SnapshotState.Present(result.snapshot)
                        stateStore.savePresent(result.snapshot)
                    }
                    RoomSnapshotReadResult.Missing -> Unit
                }
            }
            return
        }

        if (currentRoomIds.isEmpty()) {
            return
        }

        val previouslyPresentRooms =
            previousSnapshots
                .filterValues { state -> state is SnapshotState.Present }
                .keys
        (previouslyPresentRooms + currentRoomIds).forEach(::handleMarkDirty)
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

            when (val result = roomSnapshotReader.snapshot(chatId)) {
                is RoomSnapshotReadResult.Present -> {
                    val events =
                        when (val previous = previousSnapshots[chatId]) {
                            is SnapshotState.Present -> diffEngine.diff(previous.snapshot, result.snapshot)
                            SnapshotState.Missing -> diffEngine.diffRestored(result.snapshot)
                            null -> emptyList()
                        }
                    previousSnapshots[chatId] = SnapshotState.Present(result.snapshot)
                    stateStore.savePresent(result.snapshot)
                    if (events.isNotEmpty()) {
                        emitter.emit(events)
                    }
                }
                RoomSnapshotReadResult.Missing -> {
                    val previousSnapshot =
                        when (val previous = previousSnapshots[chatId]) {
                            is SnapshotState.Present -> previous.snapshot
                            SnapshotState.Missing, null -> null
                        } ?: return@repeat
                    val events = diffEngine.diffMissing(previousSnapshot)
                    if (events.isNotEmpty()) {
                        emitter.emit(events)
                    }
                    previousSnapshots[chatId] = SnapshotState.Missing
                    stateStore.saveMissing(chatId)
                }
            }
        }
    }

    private fun handleFullReconcile() {
        val currentRoomIds =
            roomSnapshotReader
                .listRoomChatIds()
                .filter { it.value > 0L }
                .toSet()

        // DB 장애로 빈 결과가 반환되면 대량 leave 이벤트 방지를 위해 스킵
        if (currentRoomIds.isEmpty() && previousSnapshots.isNotEmpty()) {
            return
        }

        val previouslyPresentRooms =
            previousSnapshots
                .filterValues { state -> state is SnapshotState.Present }
                .keys
        (previouslyPresentRooms + currentRoomIds).forEach(::handleMarkDirty)
    }

    private fun PersistedSnapshotState.toSnapshotState(): SnapshotState =
        when (this) {
            is PersistedSnapshotState.Present -> SnapshotState.Present(snapshot)
            is PersistedSnapshotState.Missing -> SnapshotState.Missing
        }
}
