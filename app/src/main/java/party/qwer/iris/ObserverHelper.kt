package party.qwer.iris

import android.database.Cursor
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.util.LinkedList
import java.util.concurrent.Executors

class ObserverHelper(
    private val db: KakaoDB,
    private val wsBroadcastFlow: MutableSharedFlow<String>,
    private val notificationReferer: String
) {
    private var lastLogId: Long = 0
    private val lastDecryptedLogs = LinkedList<Map<String, String?>>()
    private val httpRequestExecutor = Executors.newFixedThreadPool(8)
    private var mqttPublisher: MqttPublisher? = null
    private var mqttSubscriber: MqttSubscriber? = null

    init {
        initMqttPublisher()
        initMqttSubscriber()
    }

    private fun initMqttPublisher() {
        val routes = Configurable.routes
        val brokerUrl = Configurable.mqttBrokerUrl

        if (routes.isNotEmpty() && brokerUrl.isNotBlank()) {
            try {
                mqttPublisher = MqttPublisher(brokerUrl, routes)
                println("[ObserverHelper] MqttPublisher initialized: $brokerUrl")
            } catch (e: Exception) {
                System.err.println("[ObserverHelper] Failed to initialize MqttPublisher: ${e.message}")
            }
        } else {
            println("[ObserverHelper] MQTT not configured, skipping initialization")
        }
    }

    private fun initMqttSubscriber() {
        val brokerUrl = Configurable.mqttBrokerUrl

        if (brokerUrl.isNotBlank()) {
            try {
                mqttSubscriber = MqttSubscriber(brokerUrl, notificationReferer)
                println("[ObserverHelper] MqttSubscriber initialized: $brokerUrl")
            } catch (e: Exception) {
                System.err.println("[ObserverHelper] Failed to initialize MqttSubscriber: ${e.message}")
            }
        } else {
            println("[ObserverHelper] MQTT not configured, skipping subscriber initialization")
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

                        if (origin == "SYNCMSG" || origin == "MCHATLOGS") {
                            lastLogId = currentLogId
                            continue
                        }

                        val chatId = cursor.getLong(columnNames.indexOf("chat_id"))
                        val userId = cursor.getLong(columnNames.indexOf("user_id"))

                        var message = cursor.getString(columnNames.indexOf("message"))
                        var attachment = cursor.getString(columnNames.indexOf("attachment"))
                        val messageType = cursor.getString(columnNames.indexOf("type"))

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

                        val chatInfo = db.getChatInfo(chatId, userId)
                        val data = JSONObject(
                            mapOf(
                                "msg" to message,
                                "room" to chatInfo[0],
                                "sender" to chatInfo[1],
                                "json" to raw
                            )
                        ).toString()

                        runBlocking {
                            wsBroadcastFlow.emit(data)
                        }
                        mqttPublisher?.let { publisher ->
                            httpRequestExecutor.execute {
                                runBlocking {
                                    publisher.route(message, data)
                                }
                            }
                        } ?: println("[ObserverHelper] MQTT not configured, skipping publish")
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
