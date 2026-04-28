package party.qwer.iris.ingress

internal data class DispatchContinuation(
    val shouldContinue: Boolean,
    val signalPartitions: Set<Int> = emptySet(),
)

internal class IngressBacklog(
    private val partitioningPolicy: IngressPartitioningPolicy,
    private val partitionIndexForChat: (Long) -> Int,
) {
    private val lock = Any()
    private val bufferedDispatches = ArrayDeque<ChatLogDispatch>()
    private val pendingDispatches = List(partitioningPolicy.partitionCount) { ArrayDeque<ChatLogDispatch>() }
    private val orderedPendingLogIds = ArrayDeque<Long>()
    private val trackedPendingLogIds = linkedSetOf<Long>()
    private val completedLogIds = linkedSetOf<Long>()
    private val blockedDispatches = arrayOfNulls<ChatLogDispatch>(partitioningPolicy.partitionCount)
    private var activeDispatchCount = 0

    fun hasBlockedDispatch(): Boolean =
        synchronized(lock) {
            blockedDispatches.any { it != null }
        }

    fun blockedPartitions(): Set<Int> =
        synchronized(lock) {
            blockedDispatches.indices.filterTo(linkedSetOf()) { blockedDispatches[it] != null }
        }

    fun remainingCapacity(): Int =
        synchronized(lock) {
            (partitioningPolicy.maxBufferedDispatches - trackedPendingLogIds.size).coerceAtLeast(0)
        }

    fun buffer(
        dispatch: ChatLogDispatch,
        lastCommittedLogId: Long,
    ): Boolean {
        return synchronized(lock) {
            if (dispatch.logId <= lastCommittedLogId) {
                return true
            }
            if (trackedPendingLogIds.contains(dispatch.logId)) {
                return true
            }
            if (trackedPendingLogIds.size >= partitioningPolicy.maxBufferedDispatches) {
                return false
            }

            bufferedDispatches.addLast(dispatch)
            trackedPendingLogIds += dispatch.logId
            orderedPendingLogIds.addLast(dispatch.logId)
            true
        }
    }

    fun scheduleReadyPartitions(): Set<Int> =
        synchronized(lock) {
            if (bufferedDispatches.isEmpty()) {
                return emptySet()
            }

            val deferred = ArrayDeque<ChatLogDispatch>()
            val signaledPartitions = linkedSetOf<Int>()
            while (bufferedDispatches.isNotEmpty()) {
                val dispatch = bufferedDispatches.removeFirst()
                val partitionIndex = partitionIndexForChat(dispatch.logEntry.chatId)
                if (canQueueDispatchLocked(partitionIndex)) {
                    pendingDispatches[partitionIndex].addLast(dispatch)
                    signaledPartitions += partitionIndex
                } else {
                    deferred.addLast(dispatch)
                }
            }
            bufferedDispatches.addAll(deferred)
            signaledPartitions
        }

    fun takeNext(partitionIndex: Int): ChatLogDispatch? =
        synchronized(lock) {
            blockedDispatches[partitionIndex]?.let { blocked ->
                blockedDispatches[partitionIndex] = null
                activeDispatchCount += 1
                return blocked
            }

            pendingDispatches[partitionIndex].removeFirstOrNull()?.also {
                activeDispatchCount += 1
            }
        }

    fun takeBatch(partitionIndex: Int): List<ChatLogDispatch> =
        synchronized(lock) {
            blockedDispatches[partitionIndex]?.let { blocked ->
                blockedDispatches[partitionIndex] = null
                activeDispatchCount += 1
                return listOf(blocked)
            }

            val batch = mutableListOf<ChatLogDispatch>()
            while (pendingDispatches[partitionIndex].isNotEmpty()) {
                batch += pendingDispatches[partitionIndex].removeFirst()
            }
            activeDispatchCount += batch.size
            batch
        }

    fun complete(
        partitionIndex: Int,
        dispatch: ChatLogDispatch,
        outcome: DispatchOutcome,
        onAdvanceCommittedLogId: (Long) -> Unit,
    ): DispatchContinuation =
        synchronized(lock) {
            activeDispatchCount -= 1
            when (outcome) {
                DispatchOutcome.COMPLETED -> {
                    completedLogIds += dispatch.logId
                    advanceCommittedPrefixLocked(onAdvanceCommittedLogId)
                    DispatchContinuation(
                        shouldContinue = true,
                        signalPartitions = scheduleReadyPartitionsLocked(),
                    )
                }

                DispatchOutcome.RETRY_LATER -> {
                    blockedDispatches[partitionIndex] = dispatch
                    DispatchContinuation(shouldContinue = false)
                }
            }
        }

    fun completeBatch(
        partitionIndex: Int,
        dispatches: List<ChatLogDispatch>,
        outcomes: List<DispatchOutcome>,
        onAdvanceCommittedLogId: (Long) -> Unit,
    ): DispatchContinuation =
        synchronized(lock) {
            activeDispatchCount -= dispatches.size
            var shouldContinue = true
            var processedCount = 0

            for ((index, outcome) in outcomes.withIndex()) {
                val dispatch = dispatches.getOrNull(index) ?: break
                processedCount = index + 1
                when (outcome) {
                    DispatchOutcome.COMPLETED -> {
                        completedLogIds += dispatch.logId
                    }

                    DispatchOutcome.RETRY_LATER -> {
                        blockedDispatches[partitionIndex] = dispatch
                        shouldContinue = false
                        break
                    }
                }
            }

            requeueFrontLocked(partitionIndex, dispatches.drop(processedCount))
            advanceCommittedPrefixLocked(onAdvanceCommittedLogId)
            DispatchContinuation(
                shouldContinue = shouldContinue && processedCount == dispatches.size,
                signalPartitions =
                    if (shouldContinue && processedCount == dispatches.size) {
                        scheduleReadyPartitionsLocked()
                    } else {
                        emptySet()
                    },
            )
        }

    fun snapshot(
        lastPolledLogId: Long,
        lastBufferedLogId: Long,
        lastCommittedLogId: Long,
    ): IngressProgressSnapshot =
        synchronized(lock) {
            IngressProgressSnapshot(
                lastPolledLogId = lastPolledLogId,
                lastBufferedLogId = lastBufferedLogId,
                lastCommittedLogId = lastCommittedLogId,
                bufferedCount = trackedPendingLogIds.size,
                blockedPartitionCount = blockedDispatches.count { it != null },
                activeDispatchCount = activeDispatchCount,
                pendingByPartition = pendingDispatches.map { it.size },
                blockedLogIds = blockedDispatches.mapNotNull { it?.logId },
            )
        }

    private fun canQueueDispatchLocked(partitionIndex: Int): Boolean =
        blockedDispatches[partitionIndex] == null &&
            pendingDispatches[partitionIndex].size < partitioningPolicy.partitionQueueCapacity

    private fun scheduleReadyPartitionsLocked(): Set<Int> {
        if (bufferedDispatches.isEmpty()) {
            return emptySet()
        }

        val deferred = ArrayDeque<ChatLogDispatch>()
        val signaledPartitions = linkedSetOf<Int>()
        while (bufferedDispatches.isNotEmpty()) {
            val dispatch = bufferedDispatches.removeFirst()
            val partitionIndex = partitionIndexForChat(dispatch.logEntry.chatId)
            if (canQueueDispatchLocked(partitionIndex)) {
                pendingDispatches[partitionIndex].addLast(dispatch)
                signaledPartitions += partitionIndex
            } else {
                deferred.addLast(dispatch)
            }
        }
        bufferedDispatches.addAll(deferred)
        return signaledPartitions
    }

    private fun requeueFrontLocked(
        partitionIndex: Int,
        dispatches: List<ChatLogDispatch>,
    ) {
        for (index in dispatches.indices.reversed()) {
            pendingDispatches[partitionIndex].addFirst(dispatches[index])
        }
    }

    private fun advanceCommittedPrefixLocked(onAdvanceCommittedLogId: (Long) -> Unit) {
        while (orderedPendingLogIds.isNotEmpty()) {
            val nextLogId = orderedPendingLogIds.first()
            if (!completedLogIds.remove(nextLogId)) {
                return
            }
            orderedPendingLogIds.removeFirst()
            trackedPendingLogIds.remove(nextLogId)
            onAdvanceCommittedLogId(nextLogId)
        }
    }
}
