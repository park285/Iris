package party.qwer.iris

import android.database.Cursor
import org.json.JSONObject
import java.util.LinkedList
import party.qwer.iris.bridge.RedisProducer
import party.qwer.iris.bridge.RedisReplyConsumer

class ObserverHelper(
    private val db: KakaoDB,
    private val notificationReferer: String
) {
    private var lastLogId: Long = 0
    private val lastDecryptedLogs = LinkedList<Map<String, String?>>()
    private var redisProducer: RedisProducer? = null
    private var replyConsumer: RedisReplyConsumer? = null

    init {
        initBridge()
    }

    private fun initBridge() {
        try {
            val host = Configurable.mqHost
            val port = Configurable.mqPort
            val producer = RedisProducer(host, port)
            redisProducer = producer
            val consumer = RedisReplyConsumer(host, port, notificationReferer)
            consumer.start()
            replyConsumer = consumer
            println("[ObserverHelper] Redis bridge initialized: $host:$port")
        } catch (e: Exception) {
            System.err.println("[ObserverHelper] Failed to initialize Redis bridge: ${e.message}")
        }
    }

    fun checkChange(db: KakaoDB) {
        if (lastLogId == 0L) {
            lastLogId = getLastLogIdFromDB()
            println("Initial lastLogId: $lastLogId")
            return
        }

        val newLogCount = getNewLogCountFromDB()

        if (newLogCount > 0) {
            println("Detected $newLogCount new log(s). Processing...")

            db.connection.rawQuery(
                "SELECT * FROM chat_logs WHERE _id > ? ORDER BY _id ASC",
                arrayOf(lastLogId.toString())
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val columnNames = cursor.columnNames
                    val currentLogId = cursor.getLong(columnNames.indexOf("_id"))

                    if (currentLogId > lastLogId) {
                        val v = JSONObject(cursor.getString(columnNames.indexOf("v")))
                        val enc = v.getInt("enc")
                        val origin = v.getString("origin")

                        val chatId = cursor.getLong(columnNames.indexOf("chat_id"))
                        val userId = cursor.getLong(columnNames.indexOf("user_id"))

                        var message = cursor.getString(columnNames.indexOf("message"))
                        var attachment = cursor.getString(columnNames.indexOf("attachment"))
                        val messageType = cursor.getString(columnNames.indexOf("type"))

                        // Decrypt first so we can decide based on the actual text
                        try {
                            if (message.isNotEmpty() && message != "{}") message =
                                KakaoDecrypt.decrypt(enc, message, userId)
                        } catch (e: Exception) {
                            println("failed to decrypt message: $e")
                        }

                        try {
                            if ((message.contains("선물") && messageType == "71") or (attachment == null)) {
                                attachment = "{}"
                            } else if (attachment.isNotEmpty() && attachment != "{}") {
                                attachment =
                                    KakaoDecrypt.decrypt(enc, attachment, userId)
                            }
                        } catch (e: Exception) {
                            println("failed to decrypt attachment: $e")
                        }

                        // Determine if this looks like a command (after decrypt)
                        val isCommand = message?.trimStart()?.let { it.startsWith("!") || it.startsWith("/") } ?: false
                        println("[ObserverHelper] _id=$currentLogId, origin=$origin, message='${message?.take(50)}', isCommand=$isCommand")

                        // Previously skipped SYNCMSG/MCHATLOGS entirely. Keep skipping for non-commands,
                        // but allow command messages through so they can be routed.
                        if (origin == "SYNCMSG" || origin == "MCHATLOGS") {
                            if (!isCommand) {
                                println("[ObserverHelper] Skipping origin=$origin (non-command). _id=$currentLogId, chat_id=$chatId, user_id=$userId")
                                lastLogId = currentLogId
                                continue
                            } else {
                                println("[ObserverHelper] Processing command from origin=$origin: '${message.take(50)}'")
                            }
                        }

                        storeDecryptedLog(cursor, message)

                        // update log id
                        lastLogId = currentLogId

                        val raw = mutableMapOf<String, String?>()
                        for ((idx, columnName) in columnNames.withIndex()) {
                            if (columnName == "message") {
                                raw[columnName] = message
                            } else if (columnName == "attachment") {
                                raw[columnName] = attachment
                            } else {
                                raw[columnName] = cursor.getString(idx)
                            }
                        }

                        val data = try {
                            val chatInfo = db.getChatInfo(chatId, userId)
                            JSONObject(
                                mapOf(
                                    "msg" to message,
                                    "room" to chatId.toString(),
                                    "sender" to chatInfo[1],
                                    "json" to raw
                                )
                            ).toString()
                        } catch (e: Exception) {
                            println("[ObserverHelper] getChatInfo/data failed: ${e.message}, using fallback")
                            JSONObject(
                                mapOf(
                                    "msg" to message,
                                    "room" to chatId.toString(),
                                    "sender" to userId.toString(),
                                    "json" to raw
                                )
                            ).toString()
                        }

                        if (isCommand) {
                            val producer = redisProducer
                            if (producer != null) {
                                producer.route(message, data)
                            } else {
                                println("[ObserverHelper] Redis producer not available, skipping send")
                            }
                        }
                    }
                }
            }
        }
    }

    private fun getLastLogIdFromDB(): Long {
        val lastLog = db.logToDict(0)
        return lastLog["_id"]?.toLongOrNull() ?: 0;
    }

    private fun getNewLogCountFromDB(): Int {
        val res = db.executeQuery(
            "select count(*) as cnt from chat_logs where _id > ?", arrayOf(lastLogId.toString())
        )
        return res[0]["cnt"]?.toIntOrNull() ?: 0
    }

    @Synchronized
    private fun storeDecryptedLog(cursor: Cursor, decryptedMessage: String?) {
        val logEntry: MutableMap<String, String?> = HashMap()
        logEntry["_id"] = cursor.getString(cursor.getColumnIndexOrThrow("_id"))
        logEntry["chat_id"] = cursor.getString(cursor.getColumnIndexOrThrow("chat_id"))
        logEntry["user_id"] = cursor.getString(cursor.getColumnIndexOrThrow("user_id"))
        logEntry["message"] = decryptedMessage
        logEntry["created_at"] = cursor.getString(cursor.getColumnIndexOrThrow("created_at"))

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
