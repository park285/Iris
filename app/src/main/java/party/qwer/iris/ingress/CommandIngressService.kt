package party.qwer.iris.ingress

import org.json.JSONObject
import party.qwer.iris.ChatLogRepository
import party.qwer.iris.CommandKind
import party.qwer.iris.CommandParser
import party.qwer.iris.ConfigProvider
import party.qwer.iris.delivery.webhook.H2cRoutingGateway
import party.qwer.iris.IrisLogger
import party.qwer.iris.KakaoDB
import party.qwer.iris.KakaoDecrypt
import party.qwer.iris.MemberRepository
import party.qwer.iris.ParsedCommand
import party.qwer.iris.isOwnBotMessage
import party.qwer.iris.resolveObservedThreadMetadata
import party.qwer.iris.shouldSkipOrigin
import party.qwer.iris.stableLogHash
import party.qwer.iris.delivery.webhook.RoutingCommand
import party.qwer.iris.delivery.webhook.RoutingGateway
import party.qwer.iris.delivery.webhook.RoutingResult
import party.qwer.iris.persistence.CheckpointJournal
import java.io.Closeable

class CommandIngressService(
    private val db: ChatLogRepository,
    private val config: ConfigProvider,
    private val checkpointJournal: CheckpointJournal,
    private val memberRepo: MemberRepository? = null,
    private val routingGateway: RoutingGateway? = null,
    private val learnFromTimestampCorrelation: ((chatId: Long, userId: Long, messageCreatedAtMs: Long) -> Unit)? = null,
    private val onMarkDirty: (chatId: Long) -> Unit,
) : Closeable {
    @Volatile
    private var lastLogId: Long = 0L

    @Volatile
    private var dispatcher: RoutingGateway? = routingGateway

    private val senderNameCache = lruMap<SenderNameCacheKey, String>(256)
    private val roomMetadataCache = lruMap<Long, KakaoDB.RoomMetadata>(256)
    private val recentCommandFingerprints = lruMap<CommandFingerprint, Long>(MAX_COMMAND_FINGERPRINTS)

    @Volatile
    private var initBridgeBackoffUntil: Long = 0L

    init {
        if (dispatcher == null) {
            initBridge()
        }
    }

    fun checkChange() {
        if (lastLogId == 0L) {
            lastLogId = checkpointJournal.load(CHECKPOINT_STREAM_CHAT_LOGS) ?: db.latestLogId().also { seeded -> checkpointJournal.advance(CHECKPOINT_STREAM_CHAT_LOGS, seeded) }
            checkpointJournal.flushIfDirty()
            IrisLogger.debug("Initial lastLogId: $lastLogId")
            return
        }

        val newLogs = db.pollChatLogsAfter(lastLogId)
        if (newLogs.isNotEmpty()) {
            for (logEntry in newLogs) {
                onMarkDirty(logEntry.chatId)
                if (!processLogEntry(logEntry)) {
                    break
                }
            }
        }
        checkpointJournal.flushIfDirty()
    }

    internal fun lastObservedLogId(): Long = lastLogId

    override fun close() {
        runCatching {
            dispatcher?.close()
        }.onFailure {
            IrisLogger.error("[CommandIngressService] Failed to close dispatcher: ${it.message}")
        }
    }

    private fun initBridge() {
        try {
            dispatcher = H2cRoutingGateway(config)
            IrisLogger.info("[CommandIngressService] H2C dispatcher initialized")
        } catch (e: Exception) {
            IrisLogger.error("[CommandIngressService] Failed to initialize H2C dispatcher: ${e.message}")
        }
    }

    private fun ensureDispatcher(): RoutingGateway? {
        if (dispatcher != null) {
            return dispatcher
        }

        val now = System.currentTimeMillis()
        if (now < initBridgeBackoffUntil) {
            return null
        }

        synchronized(this) {
            if (dispatcher == null) {
                initBridge()
                if (dispatcher == null) {
                    initBridgeBackoffUntil = System.currentTimeMillis() + INIT_BRIDGE_BACKOFF_MS
                }
            }
            return dispatcher
        }
    }

    private fun processLogEntry(logEntry: KakaoDB.ChatLogEntry): Boolean {
        val metadata = parseMetadata(logEntry) ?: return true
        val decryptedMessage = decryptMessage(logEntry.message, metadata.enc, logEntry.userId)
        val parsedCommand = CommandParser.parse(decryptedMessage)

        logObservedEntry(logEntry, metadata.origin, parsedCommand)
        tryTimestampCorrelation(logEntry)
        if (shouldSkipOrigin(metadata.origin, parsedCommand)) {
            IrisLogger.debug(
                "[CommandIngressService] Skipping origin=${metadata.origin} for logId=${logEntry.id}, " +
                    "chatId=${logEntry.chatId}, userId=${logEntry.userId}",
            )
            return advanceLastLogId(logEntry.id)
        }

        if (!shouldRouteCommand(logEntry, parsedCommand, metadata.origin)) {
            return advanceLastLogId(logEntry.id)
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
            advanceLastLogId(logEntry.id)
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
    ): Boolean {
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

        return when (ensureDispatcher()?.route(routingCommand) ?: RoutingResult.RETRY_LATER) {
            RoutingResult.ACCEPTED,
            RoutingResult.SKIPPED,
            -> {
                rememberCommandFingerprint(logEntry, parsedCommand.normalizedText)
                advanceLastLogId(logEntry.id)
            }

            RoutingResult.RETRY_LATER -> {
                IrisLogger.error(
                    "[CommandIngressService] Failed to admit message for retryable delivery: " +
                        "chatId=${logEntry.chatId}, userId=${logEntry.userId}, logId=${logEntry.id}",
                )
                false
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

    private fun resolveRoomMetadata(chatId: Long): KakaoDB.RoomMetadata =
        cachedResolve(roomMetadataCache, chatId, KakaoDB.RoomMetadata()) { db.resolveRoomMetadata(it) }

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

    private fun advanceLastLogId(logId: Long): Boolean {
        lastLogId = logId
        checkpointJournal.advance(CHECKPOINT_STREAM_CHAT_LOGS, logId)
        return true
    }

    companion object {
        private const val MAX_COMMAND_FINGERPRINTS = 256
        private const val IMAGE_MESSAGE_TYPE = "2"
        private const val INIT_BRIDGE_BACKOFF_MS = 30_000L
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
}

private fun <K, V> lruMap(maxSize: Int): LinkedHashMap<K, V> =
    object : LinkedHashMap<K, V>(maxSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > maxSize
    }
