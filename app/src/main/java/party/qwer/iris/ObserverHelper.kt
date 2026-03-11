package party.qwer.iris

import org.json.JSONObject
import party.qwer.iris.bridge.H2cDispatcher
import party.qwer.iris.bridge.H2cDispatcher.RoutingCommand
import party.qwer.iris.bridge.H2cDispatcher.RoutingResult
import java.io.Closeable
import java.util.LinkedList

class ObserverHelper(
    private val db: KakaoDB,
) : Closeable {
    @Volatile
    private var lastLogId: Long = 0
    private val lastDecryptedLogs = LinkedList<Map<String, String?>>()
    private var dispatcher: H2cDispatcher? = null

    private val chatInfoCache =
        object : LinkedHashMap<Pair<Long, Long>, Array<String?>>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<Long, Long>, Array<String?>>?): Boolean = size > 64
        }
    private val recentCommandFingerprints =
        object : LinkedHashMap<CommandFingerprint, Long>(128, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CommandFingerprint, Long>?): Boolean = size > MAX_COMMAND_FINGERPRINTS
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

    val lastChatLogs: List<Map<String, String?>>
        @Synchronized
        get() = lastDecryptedLogs.map { HashMap(it) }

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
        val isCommand = looksLikeCommand(decryptedMessage)

        logObservedEntry(logEntry, metadata.origin, decryptedMessage, isCommand)
        if (shouldSkipOrigin(metadata.origin, isCommand)) {
            IrisLogger.debug(
                "[ObserverHelper] Skipping origin=${metadata.origin} for logId=${logEntry.id}, " +
                    "chatId=${logEntry.chatId}, userId=${logEntry.userId}",
            )
            return advanceLastLogId(logEntry.id)
        }

        storeDecryptedLog(logEntry, decryptedMessage)
        if (!shouldRouteCommand(logEntry, decryptedMessage, isCommand, metadata.origin)) {
            return advanceLastLogId(logEntry.id)
        }

        return routeCommand(logEntry, decryptedMessage)
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
        decryptedMessage: String,
        isCommand: Boolean,
    ) {
        IrisLogger.debugLazy {
            "[ObserverHelper] _id=${logEntry.id}, origin=$origin, userId=${logEntry.userId}, " +
                "message='${decryptedMessage.take(50)}', isCommand=$isCommand"
        }
    }

    private fun shouldRouteCommand(
        logEntry: KakaoDB.ChatLogEntry,
        decryptedMessage: String,
        isCommand: Boolean,
        origin: String,
    ): Boolean {
        if (!isCommand) {
            return false
        }

        if (isOwnBotMessage(logEntry.userId)) {
            IrisLogger.debug(
                "[ObserverHelper] Skipping self-authored command logId=${logEntry.id} to avoid command loop",
            )
            return false
        }

        if (isDuplicateCommand(logEntry, decryptedMessage)) {
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
        decryptedMessage: String,
    ): Boolean {
        val routingCommand =
            RoutingCommand(
                text = decryptedMessage,
                room = logEntry.chatId.toString(),
                sender = resolveSenderName(logEntry.chatId, logEntry.userId),
                userId = logEntry.userId.toString(),
                sourceLogId = logEntry.id,
            )

        return when (ensureDispatcher()?.route(routingCommand) ?: RoutingResult.RETRY_LATER) {
            RoutingResult.ACCEPTED,
            RoutingResult.SKIPPED,
            -> {
                rememberCommandFingerprint(logEntry, decryptedMessage)
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

    private fun resolveSenderName(
        chatId: Long,
        userId: Long,
    ): String =
        try {
            val cacheKey = Pair(chatId, userId)
            val chatInfo =
                chatInfoCache.getOrPut(cacheKey) {
                    db.getChatInfo(chatId, userId)
                }
            chatInfo[1]
                ?.trim()
                ?.takeUnless { it.isEmpty() || it.equals("Unknown", ignoreCase = true) }
                ?: userId.toString()
        } catch (e: Exception) {
            IrisLogger.debugLazy { "[ObserverHelper] getChatInfo failed: ${e.message}, using userId fallback" }
            userId.toString()
        }

    private fun looksLikeCommand(message: String): Boolean = message.trimStart().let { it.startsWith("!") || it.startsWith("/") }

    private fun shouldSkipOrigin(
        origin: String,
        isCommand: Boolean,
    ): Boolean = (origin == "SYNCMSG" || origin == "MCHATLOGS") && !isCommand

    private fun isOwnBotMessage(userId: Long): Boolean = Configurable.botId != 0L && userId == Configurable.botId

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

    @Synchronized
    private fun storeDecryptedLog(
        logEntry: KakaoDB.ChatLogEntry,
        decryptedMessage: String,
    ) {
        val storedLog: MutableMap<String, String?> = HashMap(5)
        storedLog["_id"] = logEntry.id.toString()
        storedLog["chat_id"] = logEntry.chatId.toString()
        storedLog["user_id"] = logEntry.userId.toString()
        storedLog["message"] = decryptedMessage
        storedLog["created_at"] = logEntry.createdAt

        lastDecryptedLogs.addFirst(storedLog)
        if (lastDecryptedLogs.size > MAX_LOGS_STORED) {
            lastDecryptedLogs.removeLast()
        }
    }

    companion object {
        private const val MAX_LOGS_STORED = 50
        private const val MAX_COMMAND_FINGERPRINTS = 256
    }

    private data class CommandFingerprint(
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
