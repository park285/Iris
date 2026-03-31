package party.qwer.iris.ingress

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.json.JSONObject
import party.qwer.iris.BotIdentityProvider
import party.qwer.iris.ChatLogRepository
import party.qwer.iris.CommandKind
import party.qwer.iris.CommandParser
import party.qwer.iris.IrisLogger
import party.qwer.iris.KakaoDB
import party.qwer.iris.KakaoDecrypt
import party.qwer.iris.MemberRepository
import party.qwer.iris.ParsedCommand
import party.qwer.iris.delivery.webhook.RoutingCommand
import party.qwer.iris.delivery.webhook.RoutingGateway
import party.qwer.iris.delivery.webhook.RoutingResult
import party.qwer.iris.isOwnBotMessage
import party.qwer.iris.persistence.CheckpointJournal
import party.qwer.iris.resolveObservedThreadMetadata
import party.qwer.iris.shouldSkipOrigin
import party.qwer.iris.stableLogHash
import java.io.Closeable
import kotlin.math.absoluteValue

private const val DEFAULT_DISPATCH_PARTITION_COUNT = 4
private const val DEFAULT_PARTITION_QUEUE_CAPACITY = 64

data class CommandIngressDispatchConfig(
    val partitionCount: Int = DEFAULT_DISPATCH_PARTITION_COUNT,
    val partitionQueueCapacity: Int = DEFAULT_PARTITION_QUEUE_CAPACITY,
    val maxBufferedDispatches: Int = partitionCount * partitionQueueCapacity,
) {
    init {
        require(partitionCount > 0) { "partitionCount must be positive" }
        require(partitionQueueCapacity > 0) { "partitionQueueCapacity must be positive" }
        require(maxBufferedDispatches > 0) { "maxBufferedDispatches must be positive" }
    }
}

internal class CommandIngressService(
    private val db: ChatLogRepository,
    private val config: BotIdentityProvider,
    private val checkpointJournal: CheckpointJournal,
    private val memberRepo: MemberRepository? = null,
    private val routingGateway: RoutingGateway,
    private val learnFromTimestampCorrelation: ((chatId: Long, userId: Long, messageCreatedAtMs: Long) -> Unit)? = null,
    dispatchDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val dispatchConfig: CommandIngressDispatchConfig = CommandIngressDispatchConfig(),
    private val onMarkDirty: (chatId: Long) -> Unit,
) : Closeable {
    private val dispatchScope = CoroutineScope(SupervisorJob() + dispatchDispatcher)
    private val dispatchSignals = List(dispatchConfig.partitionCount) { Channel<Unit>(Channel.CONFLATED) }
    private val dispatchLoopJobs: List<Job>

    private val pendingLock = Any()
    private val bufferedDispatches = ArrayDeque<DispatchRequest>()
    private val pendingDispatches = List(dispatchConfig.partitionCount) { ArrayDeque<DispatchRequest>() }
    private val orderedPendingLogIds = ArrayDeque<Long>()
    private val trackedPendingLogIds = linkedSetOf<Long>()
    private val completedLogIds = linkedSetOf<Long>()
    private val blockedDispatches = arrayOfNulls<DispatchRequest>(dispatchConfig.partitionCount)
    private var activeDispatchCount = 0

    @Volatile
    private var lastCommittedLogId: Long = 0L

    @Volatile
    private var lastPolledLogId: Long = 0L

    @Volatile
    private var lastBufferedLogId: Long = 0L

    private val senderNameCache = lruMap<SenderNameCacheKey, String>(256)
    private val roomMetadataCache = lruMap<Long, KakaoDB.RoomMetadata>(256)
    private val recentCommandFingerprints = lruMap<CommandFingerprint, Long>(MAX_COMMAND_FINGERPRINTS)

    init {
        dispatchLoopJobs =
            dispatchSignals.mapIndexed { partitionIndex, signal ->
                dispatchScope.launch {
                    for (ignored in signal) {
                        drainDispatchQueue(partitionIndex)
                    }
                }
            }
    }

    fun checkChange() {
        if (lastPolledLogId == 0L) {
            val initialLogId =
                checkpointJournal.load(CHECKPOINT_STREAM_CHAT_LOGS)
                    ?: db.latestLogId().also { seeded ->
                        checkpointJournal.advance(CHECKPOINT_STREAM_CHAT_LOGS, seeded)
                    }
            lastCommittedLogId = initialLogId
            lastPolledLogId = initialLogId
            lastBufferedLogId = initialLogId
            IrisLogger.debug("Initial lastLogId: $initialLogId")
            return
        }

        if (hasBlockedDispatch()) {
            signalBlockedDispatches()
        }

        signalDispatches(scheduleBufferedDispatches())

        val remainingBufferCapacity = remainingBufferCapacity()
        if (remainingBufferCapacity <= 0) {
            return
        }

        val newLogs = pollNewLogs(remainingBufferCapacity)
        if (newLogs.isEmpty()) {
            return
        }

        bufferPolledLogs(newLogs)

        signalDispatches(scheduleBufferedDispatches())
    }

    internal fun lastObservedLogId(): Long = lastCommittedLogId

    internal fun lastPolledLogId(): Long = lastPolledLogId

    internal fun lastBufferedLogId(): Long = lastBufferedLogId

    internal fun progressSnapshot(): IngressProgressSnapshot =
        synchronized(pendingLock) {
            IngressProgressSnapshot(
                lastPolledLogId = lastPolledLogId,
                lastBufferedLogId = lastBufferedLogId,
                lastCommittedLogId = lastCommittedLogId,
                bufferedCount = trackedPendingLogIds.size,
                blockedPartitionCount = blockedDispatches.count { it != null },
                activeDispatchCount = activeDispatchCount,
            )
        }

    override fun close() {
        dispatchSignals.forEach { signal ->
            signal.close()
        }
        dispatchLoopJobs.forEach { job ->
            job.cancel()
        }
        runCatching {
            routingGateway.close()
        }.onFailure {
            IrisLogger.error("[CommandIngressService] Failed to close dispatcher: ${it.message}")
        }
    }

    private fun hasBlockedDispatch(): Boolean =
        synchronized(pendingLock) {
            blockedDispatches.any { it != null }
        }

    private fun pollNewLogs(limit: Int): List<KakaoDB.ChatLogEntry> {
        val newLogs = db.pollChatLogsAfter(lastPolledLogId, limit)
        if (newLogs.isNotEmpty()) {
            lastPolledLogId = maxOf(lastPolledLogId, newLogs.maxOf { it.id })
        }
        return newLogs
    }

    private fun bufferPolledLogs(newLogs: List<KakaoDB.ChatLogEntry>) {
        for (logEntry in newLogs) {
            onMarkDirty(logEntry.chatId)
            if (!bufferDispatch(DispatchRequest(logEntry))) {
                break
            }
        }
    }

    private fun remainingBufferCapacity(): Int =
        synchronized(pendingLock) {
            (dispatchConfig.maxBufferedDispatches - trackedPendingLogIds.size).coerceAtLeast(0)
        }

    private fun signalBlockedDispatches() {
        blockedDispatches.indices.forEach { partitionIndex ->
            val hasBlocked =
                synchronized(pendingLock) {
                    blockedDispatches[partitionIndex] != null
                }
            if (hasBlocked) {
                dispatchSignals[partitionIndex].trySend(Unit)
            }
        }
    }

    private fun bufferDispatch(request: DispatchRequest): Boolean {
        val enqueued =
            synchronized(pendingLock) {
                if (request.logId <= lastCommittedLogId) {
                    return true
                }
                if (trackedPendingLogIds.contains(request.logId)) {
                    return true
                }
                if (trackedPendingLogIds.size >= dispatchConfig.maxBufferedDispatches) {
                    return false
                }

                bufferedDispatches.addLast(request)
                trackedPendingLogIds += request.logId
                orderedPendingLogIds.addLast(request.logId)
                if (request.logId > lastBufferedLogId) {
                    lastBufferedLogId = request.logId
                }
                true
            }
        if (!enqueued) {
            IrisLogger.error(
                "[CommandIngressService] Dispatch buffer saturated: " +
                    "chatId=${request.logEntry.chatId}, logId=${request.logId}",
            )
        }
        return enqueued
    }

    private fun scheduleBufferedDispatches(): Set<Int> {
        val signaledPartitions =
            synchronized(pendingLock) {
                scheduleBufferedDispatchesLocked()
            }
        return signaledPartitions
    }

    private fun scheduleBufferedDispatchesLocked(): Set<Int> {
        if (bufferedDispatches.isEmpty()) {
            return emptySet()
        }

        val deferred = ArrayDeque<DispatchRequest>()
        val signaledPartitions = linkedSetOf<Int>()
        while (bufferedDispatches.isNotEmpty()) {
            val request = bufferedDispatches.removeFirst()
            val partitionIndex = partitionIndexForChat(request.logEntry.chatId)
            if (canQueueDispatchLocked(partitionIndex)) {
                pendingDispatches[partitionIndex].addLast(request)
                signaledPartitions += partitionIndex
            } else {
                deferred.addLast(request)
            }
        }
        bufferedDispatches.addAll(deferred)
        return signaledPartitions
    }

    private fun canQueueDispatchLocked(partitionIndex: Int): Boolean =
        blockedDispatches[partitionIndex] == null &&
            pendingDispatches[partitionIndex].size < dispatchConfig.partitionQueueCapacity

    private fun signalDispatches(partitionIndexes: Set<Int>) {
        partitionIndexes.forEach { partitionIndex ->
            dispatchSignals[partitionIndex].trySend(Unit)
        }
    }

    private fun drainDispatchQueue(partitionIndex: Int) {
        while (true) {
            val request = takeNextDispatch(partitionIndex) ?: return
            val result = processLogEntry(request.logEntry)
            val shouldContinue = completeDispatch(partitionIndex, request, result)
            if (!shouldContinue) {
                return
            }
        }
    }

    private fun takeNextDispatch(partitionIndex: Int): DispatchRequest? =
        synchronized(pendingLock) {
            blockedDispatches[partitionIndex]?.let { blocked ->
                blockedDispatches[partitionIndex] = null
                activeDispatchCount += 1
                return blocked
            }

            pendingDispatches[partitionIndex].removeFirstOrNull()?.also {
                activeDispatchCount += 1
            }
        }

    private fun completeDispatch(
        partitionIndex: Int,
        request: DispatchRequest,
        result: DispatchResult,
    ): Boolean {
        val signalPartitions: Set<Int>
        val shouldContinue =
            synchronized(pendingLock) {
                activeDispatchCount -= 1
                when (result) {
                    DispatchResult.COMPLETED -> {
                        completedLogIds += request.logId
                        advanceCommittedPrefixLocked()
                        signalPartitions = scheduleBufferedDispatchesLocked()
                        true
                    }

                    DispatchResult.RETRY_LATER -> {
                        blockedDispatches[partitionIndex] = request
                        signalPartitions = emptySet()
                        false
                    }
                }
            }
        signalDispatches(signalPartitions)
        return shouldContinue
    }

    private fun advanceCommittedPrefixLocked() {
        while (orderedPendingLogIds.isNotEmpty()) {
            val nextLogId = orderedPendingLogIds.first()
            if (!completedLogIds.remove(nextLogId)) {
                return
            }
            orderedPendingLogIds.removeFirst()
            trackedPendingLogIds.remove(nextLogId)
            advanceCommittedLogId(nextLogId)
        }
    }

    private fun processLogEntry(logEntry: KakaoDB.ChatLogEntry): DispatchResult {
        val metadata = parseMetadata(logEntry) ?: return DispatchResult.COMPLETED
        val decryptedMessage = decryptMessage(logEntry.message, metadata.enc, logEntry.userId)
        val parsedCommand = CommandParser.parse(decryptedMessage)

        logObservedEntry(logEntry, metadata.origin, parsedCommand)
        tryTimestampCorrelation(logEntry)
        if (shouldSkipOrigin(metadata.origin, parsedCommand)) {
            IrisLogger.debug(
                "[CommandIngressService] Skipping origin=${metadata.origin} for logId=${logEntry.id}, " +
                    "chatId=${logEntry.chatId}, userId=${logEntry.userId}",
            )
            return DispatchResult.COMPLETED
        }

        if (!shouldRouteCommand(logEntry, parsedCommand, metadata.origin)) {
            return DispatchResult.COMPLETED
        }

        return routeCommand(logEntry, parsedCommand, metadata.enc)
    }

    private fun parseMetadata(logEntry: KakaoDB.ChatLogEntry): ParsedLogMetadata? =
        runCatching {
            JSONObject(logEntry.metadata)
        }.map { metadata ->
            ParsedLogMetadata(
                enc = metadata.optInt("enc", 0),
                origin = metadata.optString("origin"),
            )
        }.getOrElse { error ->
            IrisLogger.error("[CommandIngressService] Invalid metadata for logId=${logEntry.id}: ${error.message}")
            null
        }

    private fun decryptMessage(
        encryptedMessage: String,
        enc: Int,
        userId: Long,
    ): String {
        if (encryptedMessage.isEmpty() || encryptedMessage == "{}") {
            return encryptedMessage
        }

        return runCatching {
            KakaoDecrypt.decrypt(enc, encryptedMessage, userId)
        }.getOrElse { error ->
            IrisLogger.debugLazy { "[CommandIngressService] Failed to decrypt message: ${error.message}" }
            encryptedMessage
        }
    }

    private fun logObservedEntry(
        logEntry: KakaoDB.ChatLogEntry,
        origin: String,
        parsedCommand: ParsedCommand,
    ) {
        val message = parsedCommand.normalizedText
        IrisLogger.debugLazy {
            "[CommandIngressService] _id=${logEntry.id}, origin=$origin, userId=${logEntry.userId}, " +
                "messageLength=${message.length}, messageHash=${message.stableLogHash()}, kind=${parsedCommand.kind}"
        }
    }

    private fun tryTimestampCorrelation(logEntry: KakaoDB.ChatLogEntry) {
        if (learnFromTimestampCorrelation == null) return
        if (isOwnBotMessage(logEntry.userId, config.botId)) return
        val createdAtMs = logEntry.createdAt?.toLongOrNull()?.let { it * 1000 } ?: return
        learnFromTimestampCorrelation.invoke(logEntry.chatId, logEntry.userId, createdAtMs)
    }

    private fun shouldRouteCommand(
        logEntry: KakaoDB.ChatLogEntry,
        parsedCommand: ParsedCommand,
        origin: String,
    ): Boolean {
        val isImageMessage = logEntry.messageType?.trim() == IMAGE_MESSAGE_TYPE
        if (parsedCommand.kind != CommandKind.WEBHOOK && !isImageMessage) {
            return false
        }

        if (isOwnBotMessage(logEntry.userId, config.botId)) {
            IrisLogger.debug(
                "[CommandIngressService] Skipping self-authored command logId=${logEntry.id} to avoid command loop",
            )
            return false
        }

        if (isDuplicateCommand(logEntry, parsedCommand.normalizedText)) {
            IrisLogger.debug(
                "[CommandIngressService] Skipping duplicate command logId=${logEntry.id}, " +
                    "chatId=${logEntry.chatId}, origin=$origin",
            )
            return false
        }

        return true
    }

    private fun routeCommand(
        logEntry: KakaoDB.ChatLogEntry,
        parsedCommand: ParsedCommand,
        enc: Int,
    ): DispatchResult {
        val threadMetadata = resolveObservedThreadMetadata(logEntry, enc)
        val roomMetadata = resolveRoomMetadata(logEntry.chatId)
        val senderRole =
            memberRepo?.resolveSenderRole(
                logEntry.userId,
                roomMetadata.linkId.toLongOrNull(),
            )
        val routingCommand =
            RoutingCommand(
                text = parsedCommand.normalizedText,
                room = logEntry.chatId.toString(),
                sender =
                    resolveSenderName(
                        chatId = logEntry.chatId,
                        userId = logEntry.userId,
                        linkId = roomMetadata.linkId.toLongOrNull(),
                    ),
                userId = logEntry.userId.toString(),
                sourceLogId = logEntry.id,
                chatLogId = logEntry.chatLogId?.trim()?.takeIf { it.isNotEmpty() },
                roomType = roomMetadata.type.takeIf { it.isNotEmpty() },
                roomLinkId = roomMetadata.linkId.takeIf { it.isNotEmpty() },
                threadId = threadMetadata?.threadId,
                threadScope = threadMetadata?.threadScope,
                messageType = logEntry.messageType?.trim()?.takeIf { it.isNotEmpty() },
                attachment = logEntry.attachment?.takeIf { it.isNotBlank() }?.let { decryptMessage(it, enc, logEntry.userId) },
                senderRole = senderRole,
            )

        return when (routingGateway.route(routingCommand)) {
            RoutingResult.ACCEPTED,
            RoutingResult.SKIPPED,
            -> {
                rememberCommandFingerprint(logEntry, parsedCommand.normalizedText)
                DispatchResult.COMPLETED
            }

            RoutingResult.RETRY_LATER -> {
                IrisLogger.error(
                    "[CommandIngressService] Failed to admit message for retryable delivery: " +
                        "chatId=${logEntry.chatId}, userId=${logEntry.userId}, logId=${logEntry.id}",
                )
                DispatchResult.RETRY_LATER
            }
        }
    }

    private fun <K, V : Any> cachedResolve(
        cache: LinkedHashMap<K, V>,
        key: K,
        fallback: V,
        fetch: (K) -> V,
    ): V {
        synchronized(cache) { cache[key] }?.let { return it }
        val resolved =
            try {
                fetch(key)
            } catch (e: Exception) {
                IrisLogger.debugLazy { "[CommandIngressService] cachedResolve failed for key=$key: ${e.message}" }
                return fallback
            }
        return synchronized(cache) { cache.getOrPut(key) { resolved } }
    }

    private fun resolveSenderName(
        chatId: Long,
        userId: Long,
        linkId: Long?,
    ): String =
        cachedResolve(
            senderNameCache,
            SenderNameCacheKey(chatId = chatId, userId = userId),
            userId.toString(),
        ) { key ->
            memberRepo?.resolveDisplayName(
                userId = key.userId,
                chatId = key.chatId,
                linkId = linkId,
            ) ?: db.resolveSenderName(key.userId)
        }

    private fun resolveRoomMetadata(chatId: Long): KakaoDB.RoomMetadata = cachedResolve(roomMetadataCache, chatId, KakaoDB.RoomMetadata()) { db.resolveRoomMetadata(it) }

    private fun isDuplicateCommand(
        logEntry: KakaoDB.ChatLogEntry,
        decryptedMessage: String,
    ): Boolean =
        synchronized(recentCommandFingerprints) {
            recentCommandFingerprints.containsKey(commandFingerprint(logEntry, decryptedMessage))
        }

    private fun rememberCommandFingerprint(
        logEntry: KakaoDB.ChatLogEntry,
        decryptedMessage: String,
    ) {
        synchronized(recentCommandFingerprints) {
            recentCommandFingerprints[commandFingerprint(logEntry, decryptedMessage)] = logEntry.id
        }
    }

    private fun commandFingerprint(
        logEntry: KakaoDB.ChatLogEntry,
        decryptedMessage: String,
    ): CommandFingerprint =
        CommandFingerprint(
            chatId = logEntry.chatId,
            userId = logEntry.userId,
            createdAt = logEntry.createdAt.orEmpty(),
            message = decryptedMessage.trim(),
        )

    private fun advanceCommittedLogId(logId: Long) {
        lastCommittedLogId = logId
        checkpointJournal.advance(CHECKPOINT_STREAM_CHAT_LOGS, logId)
    }

    private fun partitionIndexForChat(chatId: Long): Int = chatId.hashCode().absoluteValue % dispatchConfig.partitionCount

    companion object {
        private const val MAX_COMMAND_FINGERPRINTS = 256
        private const val IMAGE_MESSAGE_TYPE = "2"
        private const val CHECKPOINT_STREAM_CHAT_LOGS = "chat_logs"
    }

    internal data class CommandFingerprint(
        val chatId: Long,
        val userId: Long,
        val createdAt: String,
        val message: String,
    )

    private data class SenderNameCacheKey(
        val chatId: Long,
        val userId: Long,
    )

    private data class ParsedLogMetadata(
        val enc: Int,
        val origin: String,
    )

    private data class DispatchRequest(
        val logEntry: KakaoDB.ChatLogEntry,
    ) {
        val logId: Long
            get() = logEntry.id
    }

    private enum class DispatchResult {
        COMPLETED,
        RETRY_LATER,
    }
}

private fun <K, V> lruMap(maxSize: Int): LinkedHashMap<K, V> =
    object : LinkedHashMap<K, V>(maxSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > maxSize
    }

internal data class IngressProgressSnapshot(
    val lastPolledLogId: Long,
    val lastBufferedLogId: Long,
    val lastCommittedLogId: Long,
    val bufferedCount: Int,
    val blockedPartitionCount: Int,
    val activeDispatchCount: Int,
)
