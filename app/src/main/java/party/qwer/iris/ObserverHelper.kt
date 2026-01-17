package party.qwer.iris

import android.database.Cursor
import org.json.JSONObject
import party.qwer.iris.bridge.RedisProducer
import party.qwer.iris.bridge.RedisReplyConsumer
import java.util.LinkedList

class ObserverHelper(
    private val db: KakaoDB,
    private val notificationReferer: String,
) {
    @Volatile
    private var lastLogId: Long = 0
    private val lastDecryptedLogs = LinkedList<Map<String, String?>>()
    private var redisProducer: RedisProducer? = null
    private var replyConsumer: RedisReplyConsumer? = null

    // LRU cache for chatInfo: key=(chatId,userId), value=Array<String?>
    private val chatInfoCache =
        object : LinkedHashMap<Pair<Long, Long>, Array<String?>>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<Long, Long>, Array<String?>>?): Boolean {
                return size > 64
            }
        }

    init {
        initBridge()
    }

    private fun initBridge() {
        try {
            val producer = RedisProducer()
            redisProducer = producer
            val consumer = RedisReplyConsumer(notificationReferer)
            consumer.start()
            replyConsumer = consumer
            IrisLogger.info("[ObserverHelper] Redis bridge initialized (shared pool)")
        } catch (e: Exception) {
            IrisLogger.error("[ObserverHelper] Failed to initialize Redis bridge: ${e.message}")
        }
    }

    fun checkChange(db: KakaoDB) {
        if (lastLogId == 0L) {
            lastLogId = getLastLogIdFromDB()
            IrisLogger.debug("Initial lastLogId: $lastLogId")
            return
        }

        // COUNT 쿼리 제거: 바로 SELECT 수행 후 결과 유무로 판단
        db.connection.rawQuery(
            "SELECT _id, chat_id, user_id, message, attachment, type, v, created_at FROM chat_logs WHERE _id > ? ORDER BY _id ASC LIMIT 100",
            arrayOf(lastLogId.toString()),
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                return // 새 로그 없음
            }

            // cache column indices
            val idxId = 0
            val idxChatId = 1
            val idxUserId = 2
            val idxMessage = 3
            val idxAttachment = 4
            val idxType = 5
            val idxV = 6
            val idxCreatedAt = 7

            do {
                val currentLogId = cursor.getLong(idxId)

                if (currentLogId > lastLogId) {
                    val v = JSONObject(cursor.getString(idxV))
                    val enc = v.getInt("enc")
                    val origin = v.getString("origin")

                    val chatId = cursor.getLong(idxChatId)
                    val userId = cursor.getLong(idxUserId)

                    var message = cursor.getString(idxMessage)
                    var attachment = cursor.getString(idxAttachment)
                    val messageType = cursor.getString(idxType)

                    // Decrypt first so we can decide based on the actual text
                    try {
                        if (message.isNotEmpty() && message != "{}") {
                            message =
                                KakaoDecrypt.decrypt(enc, message, userId)
                        }
                    } catch (e: Exception) {
                        IrisLogger.debugLazy { "failed to decrypt message: $e" }
                    }

                    try {
                        if ((message.contains("선물") && messageType == "71") or (attachment == null)) {
                            attachment = "{}"
                        } else if (attachment.isNotEmpty() && attachment != "{}") {
                            attachment =
                                KakaoDecrypt.decrypt(enc, attachment, userId)
                        }
                    } catch (e: Exception) {
                        IrisLogger.debugLazy { "failed to decrypt attachment: $e" }
                    }

                    // Determine if this looks like a command (after decrypt)
                    val isCommand = message?.trimStart()?.let { it.startsWith("!") || it.startsWith("/") } ?: false
                    IrisLogger.debugLazy {
                        "[ObserverHelper] _id=$currentLogId, origin=$origin, message='${message?.take(50)}', isCommand=$isCommand"
                    }

                    // Previously skipped SYNCMSG/MCHATLOGS entirely. Keep skipping for non-commands,
                    // but allow command messages through so they can be routed.
                    if (origin == "SYNCMSG" || origin == "MCHATLOGS") {
                        if (!isCommand) {
                            IrisLogger.debug(
                                "[ObserverHelper] Skipping origin=$origin (non-command). _id=$currentLogId, chat_id=$chatId, user_id=$userId",
                            )
                            lastLogId = currentLogId
                            continue
                        } else {
                            IrisLogger.debugLazy { "[ObserverHelper] Processing command from origin=$origin: '${message.take(50)}'" }
                        }
                    }

                    storeDecryptedLog(cursor, message, idxId, idxChatId, idxUserId, idxCreatedAt)

                    // update log id
                    lastLogId = currentLogId

                    if (isCommand) {
                        val senderName =
                            try {
                                val cacheKey = Pair(chatId, userId)
                                val chatInfo =
                                    chatInfoCache.getOrPut(cacheKey) {
                                        db.getChatInfo(chatId, userId)
                                    }
                                chatInfo[1]?.trim()?.takeIf { it.isNotEmpty() } ?: userId.toString()
                            } catch (e: Exception) {
                                IrisLogger.debugLazy { "[ObserverHelper] getChatInfo failed: ${e.message}, using fallback" }
                                userId.toString()
                            }

                        val success =
                            redisProducer?.route(
                                message,
                                chatId.toString(),
                                senderName,
                                userId.toString(),
                            ) ?: false

                        if (!success) {
                            IrisLogger.error("[ObserverHelper] Failed to route message to Redis: chatId=$chatId, userId=$userId")
                        }
                    }
                }
            } while (cursor.moveToNext())
        }
    }

    private fun getLastLogIdFromDB(): Long {
        val lastLog = db.logToDict(0)
        return lastLog["_id"]?.toLongOrNull() ?: 0
    }

    @Synchronized
    private fun storeDecryptedLog(
        cursor: Cursor,
        decryptedMessage: String?,
        idxId: Int,
        idxChatId: Int,
        idxUserId: Int,
        idxCreatedAt: Int,
    ) {
        val logEntry: MutableMap<String, String?> = HashMap(5)
        logEntry["_id"] = cursor.getString(idxId)
        logEntry["chat_id"] = cursor.getString(idxChatId)
        logEntry["user_id"] = cursor.getString(idxUserId)
        logEntry["message"] = decryptedMessage
        logEntry["created_at"] = cursor.getString(idxCreatedAt)

        lastDecryptedLogs.addFirst(logEntry)
        if (lastDecryptedLogs.size > MAX_LOGS_STORED) {
            lastDecryptedLogs.removeLast()
        }
    }

    val lastChatLogs: List<Map<String, String?>>
        get() = lastDecryptedLogs

    companion object {
        private const val MAX_LOGS_STORED = 50
    }
}
