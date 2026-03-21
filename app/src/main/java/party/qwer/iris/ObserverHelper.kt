package party.qwer.iris

import org.json.JSONObject
import party.qwer.iris.bridge.H2cDispatcher
import party.qwer.iris.bridge.RoutingCommand
import party.qwer.iris.bridge.RoutingResult
import java.io.Closeable

class ObserverHelper(
    private val db: KakaoDB,
) : Closeable {
    @Volatile
    private var lastLogId: Long = 0

    @Volatile
    private var dispatcher: H2cDispatcher? = null

    private val senderNameCache =
        object : LinkedHashMap<Long, String>(64, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<Long, String>?,
            ): Boolean = size > 64
        }
    private val roomMetadataCache =
        object : LinkedHashMap<Long, KakaoDB.RoomMetadata>(64, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<Long, KakaoDB.RoomMetadata>?,
            ): Boolean = size > 64
        }
    private val recentCommandFingerprints =
        object : LinkedHashMap<CommandFingerprint, Long>(128, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<CommandFingerprint, Long>?,
            ): Boolean = size > MAX_COMMAND_FINGERPRINTS
        }

    init {
        initBridge()
    }

    fun checkChange() {
        if (lastLogId == 0L) {
            lastLogId = db.latestLogId()
            IrisLogger.debug("Initial lastLogId: $lastLogId")
            return
        }

        val newLogs = db.pollChatLogsAfter(lastLogId)
        if (newLogs.isEmpty()) {
            return
        }

        for (logEntry in newLogs) {
            if (!processLogEntry(logEntry)) {
                return
            }
        }
    }

    override fun close() {
        runCatching {
            dispatcher?.close()
        }.onFailure {
            IrisLogger.error("[ObserverHelper] Failed to close dispatcher: ${it.message}")
        }
    }

    private fun initBridge() {
        try {
            dispatcher = H2cDispatcher()
            IrisLogger.info("[ObserverHelper] H2C dispatcher initialized")
        } catch (e: Exception) {
            IrisLogger.error("[ObserverHelper] Failed to initialize H2C dispatcher: ${e.message}")
        }
    }

    private fun ensureDispatcher(): H2cDispatcher? {
        if (dispatcher != null) {
            return dispatcher
        }

        synchronized(this) {
            if (dispatcher == null) {
                initBridge()
            }
            return dispatcher
        }
    }

    private fun processLogEntry(logEntry: KakaoDB.ChatLogEntry): Boolean {
        val metadata = parseMetadata(logEntry) ?: return true
        val decryptedMessage = decryptMessage(logEntry.message, metadata.enc, logEntry.userId)
        val parsedCommand = CommandParser.parse(decryptedMessage)

        logObservedEntry(logEntry, metadata.origin, parsedCommand)
        if (shouldSkipOrigin(metadata.origin, parsedCommand)) {
            IrisLogger.debug(
                "[ObserverHelper] Skipping origin=${metadata.origin} for logId=${logEntry.id}, " +
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
            IrisLogger.error("[ObserverHelper] Invalid metadata for logId=${logEntry.id}: ${error.message}")
            lastLogId = logEntry.id
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
            IrisLogger.debugLazy { "[ObserverHelper] Failed to decrypt message: ${error.message}" }
            encryptedMessage
        }
    }

    private fun logObservedEntry(
        logEntry: KakaoDB.ChatLogEntry,
        origin: String,
        parsedCommand: ParsedCommand,
    ) {
        IrisLogger.debugLazy {
            "[ObserverHelper] _id=${logEntry.id}, origin=$origin, userId=${logEntry.userId}, " +
                "message='${parsedCommand.normalizedText.take(50)}', kind=${parsedCommand.kind}"
        }
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

        if (isOwnBotMessage(logEntry.userId)) {
            IrisLogger.debug(
                "[ObserverHelper] Skipping self-authored command logId=${logEntry.id} to avoid command loop",
            )
            return false
        }

        if (isDuplicateCommand(logEntry, parsedCommand.normalizedText)) {
            IrisLogger.debug(
                "[ObserverHelper] Skipping duplicate command logId=${logEntry.id}, " +
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
        val routingCommand =
            RoutingCommand(
                text = parsedCommand.normalizedText,
                room = logEntry.chatId.toString(),
                sender = resolveSenderName(logEntry.userId),
                userId = logEntry.userId.toString(),
                sourceLogId = logEntry.id,
                chatLogId = logEntry.chatLogId?.trim()?.takeIf { it.isNotEmpty() },
                roomType = roomMetadata.type.takeIf { it.isNotEmpty() },
                roomLinkId = roomMetadata.linkId.takeIf { it.isNotEmpty() },
                threadId = threadMetadata?.threadId,
                threadScope = threadMetadata?.threadScope,
                messageType = logEntry.messageType?.trim()?.takeIf { it.isNotEmpty() },
                attachment = logEntry.attachment?.takeIf { it.isNotBlank() }?.let { decryptMessage(it, enc, logEntry.userId) },
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
                    "[ObserverHelper] Failed to admit message for retryable delivery: " +
                        "chatId=${logEntry.chatId}, userId=${logEntry.userId}, logId=${logEntry.id}",
                )
                false
            }
        }
    }

    private fun resolveSenderName(userId: Long): String =
        try {
            synchronized(senderNameCache) {
                senderNameCache.getOrPut(userId) {
                    db.resolveSenderName(userId)
                }
            }
        } catch (e: Exception) {
            IrisLogger.debugLazy { "[ObserverHelper] resolveSenderName failed: ${e.message}, using userId fallback" }
            userId.toString()
        }

    private fun resolveRoomMetadata(chatId: Long): KakaoDB.RoomMetadata =
        try {
            synchronized(roomMetadataCache) {
                roomMetadataCache.getOrPut(chatId) {
                    db.resolveRoomMetadata(chatId)
                }
            }
        } catch (e: Exception) {
            IrisLogger.debugLazy { "[ObserverHelper] resolveRoomMetadata failed: ${e.message}, using empty fallback" }
            KakaoDB.RoomMetadata()
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

    private fun advanceLastLogId(logId: Long): Boolean {
        lastLogId = logId
        return true
    }

    companion object {
        private const val MAX_COMMAND_FINGERPRINTS = 256
        private const val IMAGE_MESSAGE_TYPE = "2"
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

internal fun shouldSkipOrigin(
    origin: String?,
    parsedCommand: ParsedCommand,
): Boolean = (origin == "SYNCMSG" || origin == "MCHATLOGS") && parsedCommand.kind == CommandKind.NONE

internal fun isOwnBotMessage(userId: Long): Boolean = Configurable.botId != 0L && userId == Configurable.botId
