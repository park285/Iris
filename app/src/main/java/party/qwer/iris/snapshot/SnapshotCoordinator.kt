package party.qwer.iris.snapshot

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import party.qwer.iris.RoomSnapshotData
import party.qwer.iris.persistence.InMemorySnapshotStateStore
import party.qwer.iris.persistence.PersistedSnapshotState
import party.qwer.iris.persistence.SnapshotStateStore
import party.qwer.iris.storage.ChatId

internal class SnapshotCoordinator(
    scope: CoroutineScope,
    private val roomSnapshotReader: RoomSnapshotReader,
    private val diffEngine: RoomDiffEngine,
    private val emitter: SnapshotEventEmitter,
    private val stateStore: SnapshotStateStore = InMemorySnapshotStateStore(),
    private val missingPolicy: SnapshotMissingPolicy = SnapshotMissingPolicy(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private sealed interface SnapshotState {
        val chatId: ChatId

        data class Present(
            val snapshot: RoomSnapshotData,
        ) : SnapshotState {
            override val chatId: ChatId
                get() = snapshot.chatId
        }

        data class MissingPending(
            val previousSnapshot: RoomSnapshotData,
            val firstMissingAtMs: Long,
            val consecutiveMisses: Int,
        ) : SnapshotState {
            override val chatId: ChatId
                get() = previousSnapshot.chatId
        }

        data class MissingConfirmed(
            val previousSnapshot: RoomSnapshotData,
            val confirmedAtMs: Long,
        ) : SnapshotState {
            override val chatId: ChatId
                get() = previousSnapshot.chatId
        }
    }

    private data class SnapshotCoordinatorState(
        val previousSnapshots: MutableMap<ChatId, SnapshotState> = mutableMapOf(),
        val dirtyRoomSet: MutableSet<ChatId> = mutableSetOf(),
        val dirtyRoomQueue: ArrayDeque<ChatId> = ArrayDeque(),
        val pendingDebugReplies: MutableList<CompletableDeferred<SnapshotCoordinatorDebugSnapshot>> = mutableListOf(),
        var pendingSeedCache: Boolean = false,
        var pendingFullReconcile: Boolean = false,
        var pendingDrainBudget: Int = 0,
        var pendingPruneCutoffEpochMs: Long? = null,
    ) {
        fun markDirty(chatId: ChatId) {
            if (chatId.value <= 0L) return
            if (dirtyRoomSet.add(chatId)) {
                dirtyRoomQueue.addLast(chatId)
            }
        }
    }

    private val commands = Channel<SnapshotCommand>(Channel.UNLIMITED)

    @Volatile
    private var lastKnownDebugSnapshot =
        SnapshotCoordinatorDebugSnapshot(
            dirtyRoomCount = 0,
            cachedRoomCount = 0,
            pendingFullReconcile = false,
            pendingPruneCutoffEpochMs = null,
        )

    init {
        scope.launch {
            runLoop()
        }
    }

    suspend fun send(command: SnapshotCommand) {
        commands.send(command)
    }

    fun enqueue(command: SnapshotCommand) {
        commands.trySend(command)
    }

    suspend fun debugSnapshotSuspend(): SnapshotCoordinatorDebugSnapshot {
        val replyTo = CompletableDeferred<SnapshotCoordinatorDebugSnapshot>()
        send(SnapshotCommand.GetDebugSnapshot(replyTo))
        return replyTo.await()
    }

    fun dirtyRoomCount(): Int = lastKnownDebugSnapshot.dirtyRoomCount

    private suspend fun runLoop() {
        val state = SnapshotCoordinatorState()
        for (command in commands) {
            reduce(state, command)
            while (true) {
                val nextCommand = commands.tryReceive().getOrNull() ?: break
                reduce(state, nextCommand)
            }
            drainPendingCommands(state)
            val debugSnapshot = buildDebugSnapshot(state)
            lastKnownDebugSnapshot = debugSnapshot
            flushDebugReplies(state, debugSnapshot)
        }
    }

    private fun reduce(
        state: SnapshotCoordinatorState,
        command: SnapshotCommand,
    ) {
        when (command) {
            SnapshotCommand.SeedCache -> state.pendingSeedCache = true
            SnapshotCommand.FullReconcile -> state.pendingFullReconcile = true
            is SnapshotCommand.PruneMissing -> {
                val cutoffEpochMs = command.cutoffEpochMs.coerceAtLeast(0L)
                state.pendingPruneCutoffEpochMs =
                    state.pendingPruneCutoffEpochMs
                        ?.let { maxOf(it, cutoffEpochMs) }
                        ?: cutoffEpochMs
            }
            is SnapshotCommand.MarkDirty -> state.markDirty(command.chatId)
            is SnapshotCommand.Drain -> {
                state.pendingDrainBudget = maxOf(state.pendingDrainBudget, command.budget.coerceAtLeast(0))
            }
            is SnapshotCommand.GetDebugSnapshot -> state.pendingDebugReplies += command.replyTo
        }
    }

    private fun drainPendingCommands(state: SnapshotCoordinatorState) {
        while (true) {
            var handled = false

            if (state.pendingSeedCache) {
                state.pendingSeedCache = false
                handleSeedCache(state)
                handled = true
            }

            if (state.pendingFullReconcile) {
                state.pendingFullReconcile = false
                handleFullReconcile(state)
                handled = true
            }

            val pruneCutoffEpochMs = state.pendingPruneCutoffEpochMs
            if (pruneCutoffEpochMs != null) {
                state.pendingPruneCutoffEpochMs = null
                handlePruneMissing(state, pruneCutoffEpochMs)
                handled = true
            }

            val drainBudget = state.pendingDrainBudget
            if (drainBudget > 0) {
                state.pendingDrainBudget = 0
                handleDrain(state, drainBudget)
                handled = true
            }

            if (!handled) {
                return
            }
        }
    }

    private fun flushDebugReplies(
        state: SnapshotCoordinatorState,
        snapshot: SnapshotCoordinatorDebugSnapshot,
    ) {
        if (state.pendingDebugReplies.isEmpty()) {
            return
        }
        state.pendingDebugReplies.forEach { replyTo ->
            replyTo.complete(snapshot)
        }
        state.pendingDebugReplies.clear()
    }

    private fun buildDebugSnapshot(state: SnapshotCoordinatorState): SnapshotCoordinatorDebugSnapshot =
        SnapshotCoordinatorDebugSnapshot(
            dirtyRoomCount = state.dirtyRoomQueue.size,
            cachedRoomCount = state.previousSnapshots.size,
            pendingFullReconcile = state.pendingFullReconcile,
            pendingPruneCutoffEpochMs = state.pendingPruneCutoffEpochMs,
        )

    private fun handleSeedCache(state: SnapshotCoordinatorState) {
        state.previousSnapshots.clear()
        stateStore
            .loadAll()
            .forEach { (chatId, persistedState) ->
                state.previousSnapshots[chatId] = persistedState.toSnapshotState()
            }

        val currentRoomIds =
            roomSnapshotReader
                .listRoomChatIds()
                .filter { it.value > 0L }
                .toSet()

        if (state.previousSnapshots.isEmpty()) {
            currentRoomIds.forEach { chatId ->
                when (val result = roomSnapshotReader.snapshot(chatId)) {
                    is RoomSnapshotReadResult.Present -> {
                        state.previousSnapshots[chatId] = SnapshotState.Present(result.snapshot)
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

        (state.previousSnapshots.keys + currentRoomIds).forEach(state::markDirty)
    }

    private fun handleDrain(
        state: SnapshotCoordinatorState,
        budget: Int,
    ) {
        repeat(budget) {
            val chatId = state.dirtyRoomQueue.removeFirstOrNull() ?: return
            state.dirtyRoomSet.remove(chatId)

            when (val result = roomSnapshotReader.snapshot(chatId)) {
                is RoomSnapshotReadResult.Present -> {
                    val events =
                        when (val previous = state.previousSnapshots[chatId]) {
                            is SnapshotState.Present -> diffEngine.diff(previous.snapshot, result.snapshot)
                            is SnapshotState.MissingPending ->
                                if (missingPolicy.restoreQuietlyWhenPending) {
                                    emptyList()
                                } else {
                                    diffEngine.diffRestored(result.snapshot)
                                }
                            is SnapshotState.MissingConfirmed -> diffEngine.diffRestored(result.snapshot)
                            null -> emptyList()
                        }
                    state.previousSnapshots[chatId] = SnapshotState.Present(result.snapshot)
                    stateStore.savePresent(result.snapshot)
                    if (events.isNotEmpty()) {
                        emitter.emit(events)
                    }
                }
                RoomSnapshotReadResult.Missing -> {
                    handleMissingSnapshot(state, chatId)
                }
            }
        }
    }

    private fun handleMissingSnapshot(
        state: SnapshotCoordinatorState,
        chatId: ChatId,
    ) {
        when (val previous = state.previousSnapshots[chatId]) {
            is SnapshotState.Present -> {
                val pendingState =
                    SnapshotState.MissingPending(
                        previousSnapshot = previous.snapshot,
                        firstMissingAtMs = clock(),
                        consecutiveMisses = 1,
                    )
                state.previousSnapshots[chatId] = pendingState
                stateStore.saveMissingPending(
                    previousSnapshot = pendingState.previousSnapshot,
                    firstMissingAtMs = pendingState.firstMissingAtMs,
                    consecutiveMisses = pendingState.consecutiveMisses,
                )
                maybeConfirmMissing(state, pendingState)
            }

            is SnapshotState.MissingPending -> {
                val updatedPendingState =
                    previous.copy(
                        consecutiveMisses = previous.consecutiveMisses + 1,
                    )
                state.previousSnapshots[chatId] = updatedPendingState
                stateStore.saveMissingPending(
                    previousSnapshot = updatedPendingState.previousSnapshot,
                    firstMissingAtMs = updatedPendingState.firstMissingAtMs,
                    consecutiveMisses = updatedPendingState.consecutiveMisses,
                )
                maybeConfirmMissing(state, updatedPendingState)
            }

            is SnapshotState.MissingConfirmed -> Unit
            null -> Unit
        }
    }

    private fun maybeConfirmMissing(
        state: SnapshotCoordinatorState,
        pendingState: SnapshotState.MissingPending,
    ) {
        if (!shouldConfirmMissing(pendingState)) {
            return
        }
        val confirmedAtMs = clock()
        val events = diffEngine.diffMissing(pendingState.previousSnapshot)
        state.previousSnapshots[pendingState.chatId] =
            SnapshotState.MissingConfirmed(
                previousSnapshot = pendingState.previousSnapshot,
                confirmedAtMs = confirmedAtMs,
            )
        stateStore.saveMissingConfirmed(
            previousSnapshot = pendingState.previousSnapshot,
            confirmedAtMs = confirmedAtMs,
        )
        if (events.isNotEmpty()) {
            emitter.emit(events)
        }
    }

    private fun shouldConfirmMissing(pendingState: SnapshotState.MissingPending): Boolean {
        if (pendingState.consecutiveMisses >= missingPolicy.confirmAfterConsecutiveMisses) {
            return true
        }
        return clock() - pendingState.firstMissingAtMs >= missingPolicy.confirmAfterMs
    }

    private fun handleFullReconcile(state: SnapshotCoordinatorState) {
        val currentRoomIds =
            roomSnapshotReader
                .listRoomChatIds()
                .filter { it.value > 0L }
                .toSet()

        // DB 장애로 빈 결과가 반환되면 대량 leave 이벤트 방지를 위해 스킵
        if (currentRoomIds.isEmpty() && state.previousSnapshots.isNotEmpty()) {
            return
        }

        (state.previousSnapshots.keys + currentRoomIds).forEach(state::markDirty)
    }

    private fun handlePruneMissing(
        state: SnapshotCoordinatorState,
        cutoffEpochMs: Long,
    ) {
        val removedChatIds = stateStore.pruneMissingOlderThan(cutoffEpochMs)
        if (removedChatIds.isEmpty()) {
            return
        }
        removedChatIds.forEach { chatId ->
            if (state.previousSnapshots[chatId] is SnapshotState.MissingConfirmed) {
                state.previousSnapshots.remove(chatId)
            }
            if (state.dirtyRoomSet.remove(chatId)) {
                state.dirtyRoomQueue.remove(chatId)
            }
        }
    }

    private fun PersistedSnapshotState.toSnapshotState(): SnapshotState =
        when (this) {
            is PersistedSnapshotState.Present -> SnapshotState.Present(snapshot)
            is PersistedSnapshotState.MissingPending ->
                SnapshotState.MissingPending(
                    previousSnapshot = previousSnapshot,
                    firstMissingAtMs = firstMissingAtMs,
                    consecutiveMisses = consecutiveMisses,
                )

            is PersistedSnapshotState.MissingConfirmed ->
                SnapshotState.MissingConfirmed(
                    previousSnapshot = previousSnapshot,
                    confirmedAtMs = confirmedAtMs,
                )
        }
}
