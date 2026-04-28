package party.qwer.iris.ingress

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import party.qwer.iris.BotIdentityProvider
import party.qwer.iris.ChatLogRepository
import party.qwer.iris.CommandKind
import party.qwer.iris.CommandParser
import party.qwer.iris.IrisLogger
import party.qwer.iris.KakaoDB
import party.qwer.iris.KakaoDecrypt
import party.qwer.iris.KakaoDecryptBatchItem
import party.qwer.iris.MemberRepository
import party.qwer.iris.ParsedCommand
import party.qwer.iris.delivery.webhook.RoutingCommand
import party.qwer.iris.delivery.webhook.RoutingGateway
import party.qwer.iris.delivery.webhook.RoutingResult
import party.qwer.iris.isOwnBotMessage
import party.qwer.iris.persistence.CheckpointJournal
import party.qwer.iris.persistence.MemberIdentityStateStore
import party.qwer.iris.resolveObservedThreadMetadata
import party.qwer.iris.shouldSkipOrigin
import party.qwer.iris.snapshot.SnapshotEventEmitter
import party.qwer.iris.stableLogHash
import java.io.Closeable
import kotlin.math.absoluteValue

internal class CommandIngressService(
    private val db: ChatLogRepository,
    private val config: BotIdentityProvider,
    private val checkpointJournal: CheckpointJournal,
    private val memberRepo: MemberRepository? = null,
    memberIdentityStateStore: MemberIdentityStateStore? = null,
    roomEventStore: party.qwer.iris.persistence.RoomEventStore? = null,
    nicknameEventEmitter: SnapshotEventEmitter? = null,
    private val routingGateway: RoutingGateway,
    private val learnFromTimestampCorrelation: ((chatId: Long, userId: Long, messageCreatedAtMs: Long) -> Unit)? = null,
    dispatchDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val partitioningPolicy: IngressPartitioningPolicy = IngressPartitioningPolicy(),
    private val onMarkDirty: (chatId: Long) -> Unit,
) : Closeable {
    private data class NicknameRecheckKey(
        val chatId: Long,
        val userId: Long,
    )

    private val dispatchScopeJob = SupervisorJob()
    private val dispatchScope = CoroutineScope(dispatchScopeJob + dispatchDispatcher)
    private val dispatchSignals = List(partitioningPolicy.partitionCount) { Channel<Unit>(Channel.CONFLATED) }
    private val dispatchLoopJobs: List<Job>
    private val backlog = IngressBacklog(partitioningPolicy, ::partitionIndexForChat)
    private val nicknameRecheckJobs = mutableMapOf<NicknameRecheckKey, Job>()
    private val roomRecheckJobs = mutableMapOf<Long, Job>()

    @Volatile
    private var lastCommittedLogId: Long = 0L

    @Volatile
    private var lastPolledLogId: Long = 0L

    @Volatile
    private var lastBufferedLogId: Long = 0L

    private val recentCommandFingerprints = lruMap<CommandFingerprint, Long>(MAX_COMMAND_FINGERPRINTS)
    private val senderNameResolver = SenderNameResolver(db = db, memberRepo = memberRepo)
    private val roomMetadataResolver = RoomMetadataResolver(db = db)
    private val nicknameTracker =
        if (memberIdentityStateStore != null && nicknameEventEmitter != null) {
            IngressNicknameTracker(memberIdentityStateStore, roomEventStore, nicknameEventEmitter)
        } else {
            null
        }

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
        backlog.snapshot(
            lastPolledLogId = lastPolledLogId,
            lastBufferedLogId = lastBufferedLogId,
            lastCommittedLogId = lastCommittedLogId,
        )

    override fun close() {
        runBlocking { closeSuspend() }
    }

    suspend fun closeSuspend() {
        val nicknameJobs =
            synchronized(nicknameRecheckJobs) {
                nicknameRecheckJobs.values.toList().also { nicknameRecheckJobs.clear() }
            }
        nicknameJobs.forEach(Job::cancel)
        nicknameJobs.forEach { it.cancelAndJoin() }

        val roomJobs =
            synchronized(roomRecheckJobs) {
                roomRecheckJobs.values.toList().also { roomRecheckJobs.clear() }
            }
        roomJobs.forEach(Job::cancel)
        roomJobs.forEach { it.cancelAndJoin() }

        dispatchSignals.forEach { signal ->
            signal.close()
        }
        dispatchLoopJobs.forEach { job ->
            job.cancel()
        }
        dispatchLoopJobs.forEach { job ->
            job.cancelAndJoin()
        }
        dispatchScopeJob.cancelAndJoin()
        runCatching {
            routingGateway.close()
        }.onFailure {
            IrisLogger.error("[CommandIngressService] Failed to close dispatcher: ${it.message}")
        }
    }

    private fun hasBlockedDispatch(): Boolean = backlog.hasBlockedDispatch()

    private fun pollNewLogs(limit: Int): List<KakaoDB.ChatLogEntry> {
        val newLogs = db.pollChatLogsAfter(lastPolledLogId, limit)
        if (newLogs.isNotEmpty()) {
            lastPolledLogId = maxOf(lastPolledLogId, newLogs.maxOf { it.id })
        }
        return newLogs
    }

    private fun bufferPolledLogs(newLogs: List<KakaoDB.ChatLogEntry>) {
        val decryptedFields = decryptPolledLogFields(newLogs)
        for (logEntry in newLogs) {
            onMarkDirty(logEntry.chatId)
            val fields = decryptedFields[logEntry.id].orEmpty()
            if (
                !bufferDispatch(
                    ChatLogDispatch(
                        logEntry = logEntry,
                        decryptedMessage = fields["message"],
                        decryptedAttachment = fields["attachment"],
                    ),
                )
            ) {
                break
            }
        }
    }

    private fun decryptPolledLogFields(newLogs: List<KakaoDB.ChatLogEntry>): Map<Long, Map<String, String>> {
        val candidates = mutableListOf<Triple<Long, String, KakaoDecryptBatchItem>>()
        for (logEntry in newLogs) {
            val metadata = parseMetadataForBatch(logEntry) ?: continue
            if (shouldDecryptValue(logEntry.message)) {
                candidates +=
                    Triple(
                        logEntry.id,
                        "message",
                        KakaoDecryptBatchItem(metadata.enc, logEntry.message, logEntry.userId),
                    )
            }
            val attachment = logEntry.attachment
            if (attachment != null && attachment.isNotBlank() && shouldDecryptValue(attachment)) {
                candidates +=
                    Triple(
                        logEntry.id,
                        "attachment",
                        KakaoDecryptBatchItem(metadata.enc, attachment, logEntry.userId),
                    )
            }
        }
        if (candidates.isEmpty()) return emptyMap()

        val decryptedValues =
            runCatching {
                KakaoDecrypt.decryptBatch(candidates.map { it.third })
            }.getOrElse { error ->
                IrisLogger.debugLazy { "[CommandIngressService] Failed to batch decrypt polled logs: ${error.message}" }
                return emptyMap()
            }
        if (decryptedValues.size != candidates.size) return emptyMap()

        val result = linkedMapOf<Long, MutableMap<String, String>>()
        candidates.zip(decryptedValues).forEach { (candidate, decryptedValue) ->
            val (logId, fieldName) = candidate
            result.getOrPut(logId) { linkedMapOf() }[fieldName] = decryptedValue
        }
        return result
    }

    private fun remainingBufferCapacity(): Int = backlog.remainingCapacity()

    private fun signalBlockedDispatches() {
        backlog.blockedPartitions().forEach { partitionIndex ->
            dispatchSignals[partitionIndex].trySend(Unit)
        }
    }

    private fun bufferDispatch(dispatch: ChatLogDispatch): Boolean {
        val enqueued = backlog.buffer(dispatch, lastCommittedLogId)
        if (enqueued && dispatch.logId > lastBufferedLogId) {
            lastBufferedLogId = dispatch.logId
        }
        if (!enqueued) {
            IrisLogger.error(
                "[CommandIngressService] Dispatch buffer saturated: " +
                    "chatId=${dispatch.logEntry.chatId}, logId=${dispatch.logId}",
            )
        }
        return enqueued
    }

    private fun scheduleBufferedDispatches(): Set<Int> = backlog.scheduleReadyPartitions()

    private fun signalDispatches(partitionIndexes: Set<Int>) {
        partitionIndexes.forEach { partitionIndex ->
            dispatchSignals[partitionIndex].trySend(Unit)
        }
    }

    private fun drainDispatchQueue(partitionIndex: Int) {
        while (true) {
            val dispatch = backlog.takeNext(partitionIndex) ?: return
            val outcome = processLogEntry(dispatch)
            val continuation =
                backlog.complete(
                    partitionIndex = partitionIndex,
                    dispatch = dispatch,
                    outcome = outcome,
                    onAdvanceCommittedLogId = ::advanceCommittedLogId,
                )
            signalDispatches(continuation.signalPartitions)
            if (!continuation.shouldContinue) {
                return
            }
        }
    }

    private fun processLogEntry(dispatch: ChatLogDispatch): DispatchOutcome {
        val logEntry = dispatch.logEntry
        observeNicknameChange(logEntry)
        val metadata = parseMetadata(logEntry) ?: return DispatchOutcome.COMPLETED
        val decryptedMessage = dispatch.decryptedMessage ?: decryptMessage(logEntry.message, metadata.enc, logEntry.userId)
        val parsedCommand = CommandParser.parse(decryptedMessage)

        logObservedEntry(logEntry, metadata.origin, parsedCommand)
        tryTimestampCorrelation(logEntry)
        if (shouldSkipOrigin(metadata.origin, parsedCommand)) {
            IrisLogger.debug(
                "[CommandIngressService] Skipping origin=${metadata.origin} for logId=${logEntry.id}, " +
                    "chatId=${logEntry.chatId}, userId=${logEntry.userId}",
            )
            return DispatchOutcome.COMPLETED
        }

        if (!shouldRouteCommand(logEntry, parsedCommand, metadata.origin)) {
            return DispatchOutcome.COMPLETED
        }

        return routeCommand(dispatch, parsedCommand, metadata.enc)
    }

    private fun observeNicknameChange(logEntry: KakaoDB.ChatLogEntry) {
        if (nicknameTracker == null || isOwnBotMessage(logEntry.userId, config.botId)) {
            return
        }

        val metadata = roomMetadataResolver.resolveResult(logEntry.chatId)
        val linkId = metadata.metadata.linkId.toLongOrNull()

        if (linkId != null) {
            observeNicknameChangeFresh(
                chatId = logEntry.chatId,
                userId = logEntry.userId,
                linkId = linkId,
                timestamp = logEntry.createdAt?.toLongOrNull() ?: System.currentTimeMillis() / 1000,
            )
        }
        if (linkId != null || !metadata.resolved) {
            scheduleNicknameRechecks(logEntry.chatId, logEntry.userId)
        }
        scheduleRoomRechecks(logEntry.chatId)
    }

    private fun observeNicknameChangeFresh(
        chatId: Long,
        userId: Long,
        linkId: Long,
        timestamp: Long,
    ) {
        val tracker = nicknameTracker ?: return
        val currentNickname =
            runCatching {
                senderNameResolver.resolveCanonicalOpenNicknameFresh(
                    userId = userId,
                    linkId = linkId,
                )
            }.getOrElse { error ->
                IrisLogger.warn(
                    "[CommandIngressService] Failed to resolve nickname for chatId=$chatId, " +
                        "userId=$userId: ${error.message}",
                )
                return
            }

        runCatching {
            tracker.observe(
                chatId = chatId,
                userId = userId,
                linkId = linkId,
                currentNickname = currentNickname,
                timestamp = timestamp,
            )
        }.onFailure { error ->
            IrisLogger.warn(
                "[CommandIngressService] Failed to observe nickname change for chatId=$chatId, " +
                    "userId=$userId: ${error.message}",
            )
        }
    }

    private fun scheduleNicknameRechecks(
        chatId: Long,
        userId: Long,
    ) {
        val key = NicknameRecheckKey(chatId = chatId, userId = userId)
        synchronized(nicknameRecheckJobs) {
            nicknameRecheckJobs.remove(key)?.cancel()
            nicknameRecheckJobs[key] =
                dispatchScope.launch {
                    try {
                        var elapsedMs = 0L
                        for (targetDelayMs in NICKNAME_RECHECK_DELAYS_MS) {
                            delay((targetDelayMs - elapsedMs).coerceAtLeast(0L))
                            elapsedMs = targetDelayMs
                            val metadata = roomMetadataResolver.resolveResult(chatId)
                            val linkId = metadata.metadata.linkId.toLongOrNull()
                            if (!metadata.resolved) {
                                continue
                            }
                            if (linkId == null) {
                                break
                            }
                            observeNicknameChangeFresh(
                                chatId = chatId,
                                userId = userId,
                                linkId = linkId,
                                timestamp = System.currentTimeMillis() / 1000,
                            )
                        }
                    } finally {
                        synchronized(nicknameRecheckJobs) {
                            val current = nicknameRecheckJobs[key]
                            if (current == coroutineContext[Job]) {
                                nicknameRecheckJobs.remove(key)
                            }
                        }
                    }
                }
        }
    }

    private fun scheduleRoomRechecks(chatId: Long) {
        synchronized(roomRecheckJobs) {
            roomRecheckJobs.remove(chatId)?.cancel()
            roomRecheckJobs[chatId] =
                dispatchScope.launch {
                    try {
                        var elapsedMs = 0L
                        for (targetDelayMs in NICKNAME_RECHECK_DELAYS_MS) {
                            delay((targetDelayMs - elapsedMs).coerceAtLeast(0L))
                            elapsedMs = targetDelayMs
                            onMarkDirty(chatId)
                        }
                    } finally {
                        synchronized(roomRecheckJobs) {
                            val current = roomRecheckJobs[chatId]
                            if (current == coroutineContext[Job]) {
                                roomRecheckJobs.remove(chatId)
                            }
                        }
                    }
                }
        }
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

    private fun parseMetadataForBatch(logEntry: KakaoDB.ChatLogEntry): ParsedLogMetadata? =
        runCatching {
            JSONObject(logEntry.metadata)
        }.map { metadata ->
            ParsedLogMetadata(
                enc = metadata.optInt("enc", 0),
                origin = metadata.optString("origin"),
            )
        }.getOrNull()

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

    private fun shouldDecryptValue(value: String): Boolean = value.isNotEmpty() && value != "{}"

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
        dispatch: ChatLogDispatch,
        parsedCommand: ParsedCommand,
        enc: Int,
    ): DispatchOutcome {
        val logEntry = dispatch.logEntry
        val threadMetadata = resolveObservedThreadMetadata(logEntry, enc)
        val roomMetadata = roomMetadataResolver.resolve(logEntry.chatId)
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
                    senderNameResolver.resolve(
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
                attachment =
                    dispatch.decryptedAttachment
                        ?: logEntry.attachment?.takeIf { it.isNotBlank() }?.let { decryptMessage(it, enc, logEntry.userId) },
                senderRole = senderRole,
            )

        return when (routingGateway.route(routingCommand)) {
            RoutingResult.ACCEPTED,
            RoutingResult.SKIPPED,
            -> {
                rememberCommandFingerprint(logEntry, parsedCommand.normalizedText)
                DispatchOutcome.COMPLETED
            }

            RoutingResult.RETRY_LATER -> {
                IrisLogger.error(
                    "[CommandIngressService] Failed to admit message for retryable delivery: " +
                        "chatId=${logEntry.chatId}, userId=${logEntry.userId}, logId=${logEntry.id}",
                )
                DispatchOutcome.RETRY_LATER
            }
        }
    }

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

    private fun partitionIndexForChat(chatId: Long): Int = chatId.hashCode().absoluteValue % partitioningPolicy.partitionCount

    companion object {
        private const val MAX_COMMAND_FINGERPRINTS = 256
        private const val IMAGE_MESSAGE_TYPE = "2"
        private const val CHECKPOINT_STREAM_CHAT_LOGS = "chat_logs"
        private val NICKNAME_RECHECK_DELAYS_MS = listOf(250L, 1_000L, 3_000L, 10_000L, 30_000L, 60_000L)
    }

    internal data class CommandFingerprint(
        val chatId: Long,
        val userId: Long,
        val createdAt: String,
        val message: String,
    )

    private data class ParsedLogMetadata(
        val enc: Int,
        val origin: String,
    )
}

internal fun <K, V> lruMap(maxSize: Int): LinkedHashMap<K, V> =
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
    val pendingByPartition: List<Int> = emptyList(),
    val blockedLogIds: List<Long> = emptyList(),
)
