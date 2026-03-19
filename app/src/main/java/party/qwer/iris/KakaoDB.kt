package party.qwer.iris

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
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
            attachAuxiliaryDatabases(openedConnection)
            ensureObservedProfileTable(openedConnection)
            connection = openedConnection
            when (val resolution = resolveBotUserId(detectBotUserId(), Configurable.botId)) {
                is BotUserIdResolution.Detected -> {
                    IrisLogger.info("Bot user_id is detected from chat_logs: ${resolution.botId}")
                    Configurable.botId = resolution.botId
                }

                is BotUserIdResolution.ConfigFallback -> {
                    IrisLogger.info("Using configured bot user_id fallback: ${resolution.botId}")
                }

                BotUserIdResolution.Missing -> {
                    IrisLogger.error(
                        "Warning: Bot user_id not found in chat_logs and no configured fallback exists. " +
                            "Decryption might not work correctly.",
                    )
                }
            }
        } catch (e: SQLiteException) {
            IrisLogger.error("SQLiteException: ${e.message}", e)
            throw IllegalStateException("You don't have a permission to access KakaoTalk Database.", e)
        }
    }

    private fun detectBotUserId(): Long = detectBotUserIdByStringMatch().takeIf { it > 0L } ?: detectBotUserIdByJsonFallback()

    private fun detectBotUserIdByStringMatch(): Long =
        withPrimaryConnection { db ->
            db
                .rawQuery(
                    """
                    SELECT user_id
                    FROM chat_logs
                    WHERE user_id > 0
                      AND v LIKE '%"isMine":true%'
                    ORDER BY _id DESC
                    LIMIT 1
                    """.trimIndent(),
                    null,
                ).use { cursor ->
                    if (cursor.moveToFirst()) {
                        cursor.getLong(0)
                    } else {
                        0L
                    }
                }
        }

    private fun detectBotUserIdByJsonFallback(): Long =
        withPrimaryConnection { db ->
            db
                .rawQuery(
                    """
                    SELECT user_id, v
                    FROM chat_logs
                    WHERE user_id > 0
                      AND v IS NOT NULL
                      AND v != ''
                      AND v LIKE '%"isMine"%'
                    ORDER BY _id DESC
                    LIMIT $BOT_USER_ID_FALLBACK_SCAN_LIMIT
                    """.trimIndent(),
                    null,
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val userId = cursor.getLong(0)
                        val metadata = cursor.getString(1).orEmpty()
                        if (!metadata.contains("\"isMine\"")) {
                            continue
                        }
                        val isMine =
                            runCatching {
                                JSONObject(metadata).optBoolean("isMine", false)
                            }.getOrDefault(false)
                        if (isMine) {
                            return@use userId
                        }
                    }
                    0L
                }
        }

    fun resolveSenderName(userId: Long): String {
        if (userId == Configurable.botId) {
            return Configurable.botName
        }

        return getNameOfUserId(userId)
            ?.trim()
            ?.takeUnless { it.isEmpty() || it.equals("Unknown", ignoreCase = true) }
            ?: userId.toString()
    }

    private fun getNameOfUserId(userId: Long): String? {
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
                    IrisLogger.debugLazy { "[getNameOfUserId] No user found for userId=$userId" }
                    return@use "Unknown"
                }

                val encryptedName = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                val enc = cursor.getInt(cursor.getColumnIndexOrThrow("enc"))
                if (encryptedName == null) {
                    IrisLogger.debugLazy { "[getNameOfUserId] name column is null for userId=$userId" }
                    return@use "Unknown"
                }

                val botId = Configurable.botId
                if (botId <= 0L) {
                    return@use encryptedName
                }

                try {
                    KakaoDecrypt.decrypt(enc, encryptedName, botId)
                } catch (e: Exception) {
                    IrisLogger.debugLazy { "Decryption error in getNameOfUserId: $e" }
                    encryptedName
                }
            }
        }
    }

    fun resolveRoomMetadata(chatId: Long): RoomMetadata =
        withPrimaryConnection { db ->
            db.rawQuery("SELECT type, link_id FROM chat_rooms WHERE id = ?", arrayOf(chatId.toString())).use { cursor ->
                if (!cursor.moveToFirst()) {
                    return@use RoomMetadata()
                }
                RoomMetadata(
                    type = cursor.getString(0)?.trim().orEmpty(),
                    linkId = cursor.getString(1)?.trim().orEmpty(),
                )
            }
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
                    SELECT *
                    FROM chat_logs
                    WHERE _id > ?
                    ORDER BY _id ASC
                    LIMIT $effectiveLimit
                    """.trimIndent(),
                    arrayOf(afterLogId.toString()),
                ).use { cursor ->
                    val rows = ArrayList<ChatLogEntry>(effectiveLimit)
                    val idIndex = cursor.getColumnIndexOrThrow("_id")
                    val chatLogIdIndex = cursor.getColumnIndex("id")
                    val chatIdIndex = cursor.getColumnIndexOrThrow("chat_id")
                    val userIdIndex = cursor.getColumnIndexOrThrow("user_id")
                    val messageIndex = cursor.getColumnIndexOrThrow("message")
                    val metadataIndex = cursor.getColumnIndexOrThrow("v")
                    val createdAtIndex = cursor.getColumnIndexOrThrow("created_at")
                    val messageTypeIndex = cursor.getColumnIndex("type")
                    val threadIdIndex = cursor.getColumnIndex("thread_id")
                    val supplementIndex = cursor.getColumnIndex("supplement")
                    while (cursor.moveToNext()) {
                        rows.add(
                            ChatLogEntry(
                                id = cursor.getLong(idIndex),
                                chatLogId = cursor.getOptionalString(chatLogIdIndex),
                                chatId = cursor.getLong(chatIdIndex),
                                userId = cursor.getLong(userIdIndex),
                                message = cursor.getString(messageIndex).orEmpty(),
                                metadata = cursor.getString(metadataIndex).orEmpty(),
                                createdAt = cursor.getString(createdAtIndex),
                                messageType = cursor.getOptionalString(messageTypeIndex),
                                threadId = cursor.getOptionalString(threadIdIndex),
                                supplement = cursor.getOptionalString(supplementIndex),
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

    fun upsertObservedProfile(identity: KakaoNotificationIdentity) {
        val updatedAt = System.currentTimeMillis()
        synchronized(dbLock) {
            connection.execSQL(
                """
                INSERT OR REPLACE INTO db3.observed_profiles (
                    stable_id,
                    display_name,
                    room_name,
                    notification_key,
                    posted_at,
                    updated_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf(
                    identity.stableId,
                    identity.displayName,
                    identity.roomName,
                    identity.notificationKey,
                    identity.postedAt,
                    updatedAt,
                ),
            )
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
                    cursor.moveToFirst()
                }
        }

    private inline fun <T> withPrimaryConnection(block: (SQLiteDatabase) -> T): T =
        synchronized(dbLock) {
            block(connection)
        }

    private fun ensureObservedProfileTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS db3.observed_profiles (
                stable_id TEXT PRIMARY KEY,
                display_name TEXT NOT NULL,
                room_name TEXT NOT NULL,
                notification_key TEXT NOT NULL,
                posted_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }

    private fun openDetachedReadConnection(): SQLiteDatabase {
        val queryConnection =
            SQLiteDatabase.openDatabase(
                "$DB_PATH/KakaoTalk.db",
                null,
                SQLiteDatabase.OPEN_READONLY,
            )
        attachAuxiliaryDatabases(queryConnection)
        return queryConnection
    }

    private fun attachAuxiliaryDatabases(db: SQLiteDatabase) {
        db.execSQL("ATTACH DATABASE '$DB_PATH/KakaoTalk2.db' AS db2")
        db.execSQL("ATTACH DATABASE '$DB_PATH/multi_profile_database.db' AS db3")
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
            val columnIndices =
                IntArray(columnNames.size) { index ->
                    cursor.getColumnIndexOrThrow(columnNames[index])
                }
            while (cursor.moveToNext() && resultList.size < maxRows) {
                val row: MutableMap<String, String?> = HashMap(columnNames.size)
                for (index in columnNames.indices) {
                    row[columnNames[index]] = cursor.getString(columnIndices[index])
                }
                resultList.add(row)
            }
        }
        return resultList
    }

    data class ChatLogEntry(
        val id: Long,
        val chatLogId: String? = null,
        val chatId: Long,
        val userId: Long,
        val message: String,
        val metadata: String,
        val createdAt: String?,
        val messageType: String? = null,
        val threadId: String? = null,
        val supplement: String? = null,
    )

    data class RoomMetadata(
        val type: String = "",
        val linkId: String = "",
    )

    companion object {
        private val DB_PATH = "${PathUtils.getAppPath()}databases"
        const val DEFAULT_QUERY_RESULT_LIMIT = 500
        const val DEFAULT_POLL_BATCH_SIZE = 100
        private const val BOT_USER_ID_FALLBACK_SCAN_LIMIT = 200
        private val MESSAGE_DECRYPT_KEYS = arrayOf("message", "attachment")
        private val PROFILE_DECRYPT_KEYWORDS =
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
        private val PROFILE_DECRYPT_KEYS =
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

        fun decryptRow(row: Map<String, String?>): Map<String, String?> {
            @Suppress("NAME_SHADOWING")
            var row = row.toMutableMap()

            try {
                row = decryptMessageFields(row)
                row = decryptProfileFields(row)
            } catch (e: Exception) {
                IrisLogger.error("JSON processing error during decryption: $e")
            }

            return row
        }

        private fun decryptMessageFields(row: MutableMap<String, String?>): MutableMap<String, String?> {
            if (!row.contains("message") && !row.contains("attachment")) {
                return row
            }

            val vStr = row.getOrDefault("v", "")
            if (vStr?.isNotEmpty() != true) {
                return row
            }

            return try {
                val vJson = JSONObject(vStr)
                val enc = vJson.optInt("enc", 0)
                val userId = row["user_id"]?.toLongOrNull() ?: Configurable.botId
                if (userId > 0L) {
                    decryptRowValues(row, enc, userId, MESSAGE_DECRYPT_KEYS)
                } else {
                    row
                }
            } catch (e: JSONException) {
                IrisLogger.error("Error parsing 'v' for decryption: $e")
                row
            }
        }

        private fun decryptProfileFields(row: MutableMap<String, String?>): MutableMap<String, String?> {
            if (!row.contains("enc") || !PROFILE_DECRYPT_KEYWORDS.any { row.contains(it) }) {
                return row
            }

            val botId = Configurable.botId
            if (botId <= 0L) {
                return row
            }

            val enc = row["enc"]?.toIntOrNull() ?: 0
            return decryptRowValues(row, enc, botId, PROFILE_DECRYPT_KEYS)
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

private fun android.database.Cursor.getOptionalString(index: Int): String? = index.takeIf { it >= 0 }?.let { getString(it) }

internal sealed interface BotUserIdResolution {
    val botId: Long

    data class Detected(
        override val botId: Long,
    ) : BotUserIdResolution

    data class ConfigFallback(
        override val botId: Long,
    ) : BotUserIdResolution

    data object Missing : BotUserIdResolution {
        override val botId: Long = 0L
    }
}

internal fun resolveBotUserId(
    detectedBotId: Long,
    configuredBotId: Long,
): BotUserIdResolution =
    when {
        detectedBotId > 0L -> BotUserIdResolution.Detected(detectedBotId)
        configuredBotId > 0L -> BotUserIdResolution.ConfigFallback(configuredBotId)
        else -> BotUserIdResolution.Missing
    }
