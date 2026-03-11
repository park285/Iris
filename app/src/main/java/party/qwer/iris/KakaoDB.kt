package party.qwer.iris

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONException
import org.json.JSONObject

class KakaoDB {
    private val connection: SQLiteDatabase
    private val dbLock = Any()

    init {
        try {
            val openedConnection =
                SQLiteDatabase.openDatabase(
                    "$DB_PATH/KakaoTalk.db",
                    null,
                    SQLiteDatabase.OPEN_READWRITE,
                )
            openedConnection.execSQL("ATTACH DATABASE '$DB_PATH/KakaoTalk2.db' AS db2")
            openedConnection.execSQL("ATTACH DATABASE '$DB_PATH/multi_profile_database.db' AS db3")
            connection = openedConnection
            Configurable.botId = botUserId
        } catch (e: SQLiteException) {
            IrisLogger.error("SQLiteException: ${e.message}", e)
            throw IllegalStateException("You don't have a permission to access KakaoTalk Database.", e)
        }
    }

    val botUserId: Long
        get() =
            withPrimaryConnection { db ->
                db
                    .rawQuery(
                        """
                        SELECT user_id
                        FROM chat_logs
                        WHERE v LIKE '%"isMine":true%'
                        ORDER BY _id DESC
                        LIMIT 1
                        """.trimIndent(),
                        null,
                    ).use { cursor ->
                        if (cursor.moveToFirst()) {
                            val detectedBotUserId = cursor.getLong(0)
                            IrisLogger.info("Bot user_id is detected: $detectedBotUserId")
                            detectedBotUserId
                        } else {
                            IrisLogger.error(
                                "Warning: Bot user_id not found in chat_logs with isMine:true. " +
                                    "Decryption might not work correctly.",
                            )
                            0L
                        }
                    }
            }

    fun getNameOfUserId(userId: Long): String? {
        val bindArgs = arrayOf(userId.toString())
        val sql =
            if (hasOpenChatMember) {
                """
                WITH info AS (SELECT ? AS user_id)
                SELECT COALESCE(open_chat_member.nickname, friends.name) AS name,
                       COALESCE(open_chat_member.enc, friends.enc) AS enc
                FROM info
                LEFT JOIN db2.open_chat_member ON open_chat_member.user_id = info.user_id
                LEFT JOIN db2.friends ON friends.id = info.user_id
                """.trimIndent()
            } else {
                "SELECT name, enc FROM db2.friends WHERE id = ?"
            }

        return withPrimaryConnection { db ->
            db.rawQuery(sql, bindArgs).use { cursor ->
                if (!cursor.moveToNext()) {
                    IrisLogger.error("[getNameOfUserId] No user found for userId=$userId")
                    return@use "Unknown"
                }

                val encryptedName = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                val enc = cursor.getInt(cursor.getColumnIndexOrThrow("enc"))
                if (encryptedName == null) {
                    IrisLogger.error("[getNameOfUserId] name column is null for userId=$userId")
                    return@use "Unknown"
                }

                try {
                    KakaoDecrypt.decrypt(enc, encryptedName, Configurable.botId)
                } catch (e: Exception) {
                    IrisLogger.error("Decryption error in getNameOfUserId: $e")
                    encryptedName
                }
            }
        }
    }

    fun getChatInfo(
        chatId: Long,
        userId: Long,
    ): Array<String?> {
        val sender =
            if (userId == Configurable.botId) {
                Configurable.botName
            } else {
                getNameOfUserId(userId)
            }

        withPrimaryConnection { db ->
            db
                .rawQuery(
                    "SELECT private_meta FROM chat_rooms WHERE id = ?",
                    arrayOf(chatId.toString()),
                ).use { cursor ->
                    if (cursor.moveToNext()) {
                        val value = cursor.getString(0)
                        if (!value.isNullOrEmpty()) {
                            try {
                                val meta = Json.decodeFromString<Map<String, JsonElement>>(value)
                                val name = meta["name"]?.jsonPrimitive?.content
                                if (!name.isNullOrBlank()) {
                                    return arrayOf(name, sender)
                                }
                            } catch (_: Exception) {
                            }
                        }
                    }
                }

            db
                .rawQuery(
                    "SELECT name FROM db2.open_link WHERE id = (SELECT link_id FROM chat_rooms WHERE id = ?)",
                    arrayOf(chatId.toString()),
                ).use { cursor ->
                    if (cursor.moveToNext()) {
                        return arrayOf(cursor.getString(0), sender)
                    }
                }
        }

        return arrayOf(sender, sender)
    }

    fun latestLogId(): Long =
        withPrimaryConnection { db ->
            db.rawQuery("SELECT _id FROM chat_logs ORDER BY _id DESC LIMIT 1", null).use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getLong(0)
                } else {
                    0L
                }
            }
        }

    fun pollChatLogsAfter(
        afterLogId: Long,
        limit: Int = DEFAULT_POLL_BATCH_SIZE,
    ): List<ChatLogEntry> {
        val effectiveLimit = limit.coerceIn(1, DEFAULT_POLL_BATCH_SIZE)
        return withPrimaryConnection { db ->
            db
                .rawQuery(
                    """
                    SELECT _id, chat_id, user_id, message, attachment, type, v, created_at
                    FROM chat_logs
                    WHERE _id > ?
                    ORDER BY _id ASC
                    LIMIT $effectiveLimit
                    """.trimIndent(),
                    arrayOf(afterLogId.toString()),
                ).use { cursor ->
                    val rows = ArrayList<ChatLogEntry>(effectiveLimit)
                    while (cursor.moveToNext()) {
                        rows.add(
                            ChatLogEntry(
                                id = cursor.getLong(0),
                                chatId = cursor.getLong(1),
                                userId = cursor.getLong(2),
                                message = cursor.getString(3).orEmpty(),
                                attachment = cursor.getString(4),
                                type = cursor.getString(5),
                                metadata = cursor.getString(6).orEmpty(),
                                createdAt = cursor.getString(7),
                            ),
                        )
                    }
                    rows
                }
        }
    }

    fun closeConnection() {
        synchronized(dbLock) {
            if (connection.isOpen) {
                connection.close()
                IrisLogger.info("Database connection closed.")
            }
        }
    }

    fun executeQuery(
        sqlQuery: String,
        bindArgs: Array<String?>?,
        maxRows: Int = DEFAULT_QUERY_RESULT_LIMIT,
    ): List<Map<String, String?>> {
        val queryConnection = openDetachedReadConnection()
        return try {
            readQueryRows(queryConnection, sqlQuery, bindArgs, maxRows.coerceAtLeast(1))
        } finally {
            queryConnection.close()
        }
    }

    private val hasOpenChatMember: Boolean by lazy { checkNewDb() }

    private fun checkNewDb(): Boolean =
        withPrimaryConnection { db ->
            db
                .rawQuery(
                    "SELECT name FROM db2.sqlite_master WHERE type='table' AND name='open_chat_member'",
                    null,
                ).use { cursor ->
                    cursor.count > 0
                }
        }

    private inline fun <T> withPrimaryConnection(block: (SQLiteDatabase) -> T): T =
        synchronized(dbLock) {
            block(connection)
        }

    private fun openDetachedReadConnection(): SQLiteDatabase {
        val queryConnection =
            SQLiteDatabase.openDatabase(
                "$DB_PATH/KakaoTalk.db",
                null,
                SQLiteDatabase.OPEN_READONLY,
            )
        queryConnection.execSQL("ATTACH DATABASE '$DB_PATH/KakaoTalk2.db' AS db2")
        queryConnection.execSQL("ATTACH DATABASE '$DB_PATH/multi_profile_database.db' AS db3")
        return queryConnection
    }

    private fun readQueryRows(
        queryConnection: SQLiteDatabase,
        sqlQuery: String,
        bindArgs: Array<String?>?,
        maxRows: Int,
    ): List<Map<String, String?>> {
        val resultList: MutableList<Map<String, String?>> = ArrayList(minOf(maxRows, 64))
        queryConnection.rawQuery(sqlQuery, bindArgs).use { cursor ->
            val columnNames = cursor.columnNames
            while (cursor.moveToNext() && resultList.size < maxRows) {
                val row: MutableMap<String, String?> = HashMap()
                for (columnName in columnNames) {
                    val columnIndex = cursor.getColumnIndexOrThrow(columnName)
                    row[columnName] = cursor.getString(columnIndex)
                }
                resultList.add(row)
            }
        }
        return resultList
    }

    data class ChatLogEntry(
        val id: Long,
        val chatId: Long,
        val userId: Long,
        val message: String,
        val attachment: String?,
        val type: String?,
        val metadata: String,
        val createdAt: String?,
    )

    companion object {
        private val DB_PATH = "${PathUtils.getAppPath()}databases"
        const val DEFAULT_QUERY_RESULT_LIMIT = 500
        const val DEFAULT_POLL_BATCH_SIZE = 100

        fun decryptRow(row: Map<String, String?>): Map<String, String?> {
            @Suppress("NAME_SHADOWING")
            var row = row.toMutableMap()

            try {
                if (row.contains("message") || row.contains("attachment")) {
                    val vStr = row.getOrDefault("v", "")
                    if (vStr?.isNotEmpty() == true) {
                        try {
                            val vJson = JSONObject(vStr)
                            val enc = vJson.optInt("enc", 0)
                            val userId = row["user_id"]?.toLongOrNull() ?: Configurable.botId
                            val keysToDecrypt = arrayOf("message", "attachment")
                            row = decryptRowValues(row, enc, userId, keysToDecrypt)
                        } catch (e: JSONException) {
                            IrisLogger.error("Error parsing 'v' for decryption: $e")
                        }
                    }
                }

                val keywords =
                    listOf(
                        "name",
                        "nick_name",
                        "nickname",
                        "profile_image_url",
                        "full_profile_image_url",
                        "original_profile_image_url",
                        "status_message",
                        "contact_name",
                        "board_v",
                    )

                if (row.contains("enc") && keywords.any { row.contains(it) }) {
                    val botId = Configurable.botId
                    val enc = row["enc"]?.toIntOrNull() ?: 0
                    val keysToDecrypt =
                        arrayOf(
                            "nick_name",
                            "name",
                            "nickname",
                            "profile_image_url",
                            "full_profile_image_url",
                            "original_profile_image_url",
                            "status_message",
                            "contact_name",
                            "v",
                            "board_v",
                        )
                    row = decryptRowValues(row, enc, botId, keysToDecrypt)
                }
            } catch (e: Exception) {
                IrisLogger.error("JSON processing error during decryption: $e")
            }

            return row
        }

        private fun decryptRowValues(
            row: MutableMap<String, String?>,
            enc: Int,
            botId: Long,
            keysToDecrypt: Array<String>,
        ): MutableMap<String, String?> {
            for (key in keysToDecrypt) {
                if (row.containsKey(key)) {
                    try {
                        val encryptedValue = row.getOrDefault(key, "")
                        if (encryptedValue != "{}" && encryptedValue != "[]") {
                            encryptedValue?.let {
                                row[key] = KakaoDecrypt.decrypt(enc, it, botId)
                            }
                        }
                    } catch (e: Exception) {
                        IrisLogger.error("Decryption error for $key: $e")
                    }
                }
            }
            return row
        }
    }
}
