package party.qwer.iris

import android.database.sqlite.SQLiteDatabase
import java.io.Closeable
import java.io.File

internal data class ObservedProfileRecord(
    val stableId: String,
    val displayName: String,
    val roomName: String,
)

internal data class ObservedProfileUserLink(
    val stableId: String,
    val userId: Long,
    val chatId: Long,
    val displayName: String,
    val roomName: String,
)

internal fun extractChatIdFromNotificationKey(notificationKey: String): Long? {
    val parts = notificationKey.split('|')
    if (parts.size < 4) {
        return null
    }
    return parts[3].toLongOrNull()
}

internal fun matchObservedProfileUserLinks(
    chatId: Long,
    observedProfiles: List<ObservedProfileRecord>,
    userDisplayNames: Map<Long, String>,
): List<ObservedProfileUserLink> {
    val uniqueNames =
        userDisplayNames
            .entries
            .groupBy({ it.value.trim() }, { it.key })
            .filterKeys { it.isNotBlank() }
            .mapValues { (_, userIds) -> userIds.distinct().singleOrNull() }
            .filterValues { it != null }
            .mapValues { (_, userId) -> userId!! }

    if (uniqueNames.isEmpty()) {
        return emptyList()
    }

    return observedProfiles.mapNotNull { profile ->
        val normalizedName = profile.displayName.trim()
        val userId = uniqueNames[normalizedName] ?: return@mapNotNull null
        profile.stableId.takeIf { it.isNotBlank() }?.let { stableId ->
            ObservedProfileUserLink(
                stableId = stableId,
                userId = userId,
                chatId = chatId,
                displayName = normalizedName,
                roomName = profile.roomName.trim(),
            )
        }
    }
}

internal class IrisMetadataStore(
    private val databasePath: String = "${PathUtils.getAppPath()}databases/iris.db",
) : ProfileRepository,
    Closeable {
    private val db: SQLiteDatabase
    private val dbLock = Any()

    init {
        val targetFile = File(databasePath)
        targetFile.parentFile?.mkdirs()
        db =
            SQLiteDatabase.openDatabase(
                targetFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY,
            )
        ensureObservedProfileTable(db)
        ensureObservedProfileUserLinkTable(db)
        migrateUserLinksIfNeeded(db)
    }

    override fun upsertObservedProfile(identity: KakaoNotificationIdentity) {
        val updatedAt = System.currentTimeMillis()
        synchronized(dbLock) {
            db.execSQL(
                """
                INSERT OR REPLACE INTO observed_profiles (
                    stable_id,
                    display_name,
                    room_name,
                    chat_id,
                    notification_key,
                    posted_at,
                    updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any?>(
                    identity.stableId,
                    identity.displayName,
                    identity.roomName,
                    extractChatIdFromNotificationKey(identity.notificationKey),
                    identity.notificationKey,
                    identity.postedAt,
                    updatedAt,
                ),
            )
        }
    }

    override fun learnObservedProfileUserMappings(
        chatId: Long,
        userDisplayNames: Map<Long, String>,
    ) {
        if (userDisplayNames.isEmpty()) {
            return
        }
        val updatedAt = System.currentTimeMillis()
        synchronized(dbLock) {
            val cursor =
                db.rawQuery(
                    """
                    SELECT stable_id, display_name, room_name
                    FROM observed_profiles
                    WHERE chat_id = ?
                    ORDER BY updated_at DESC
                    """.trimIndent(),
                    arrayOf(chatId.toString()),
                )
            val observedProfiles =
                cursor.use { observedCursor ->
                    buildList {
                        while (observedCursor.moveToNext()) {
                            add(
                                ObservedProfileRecord(
                                    stableId = observedCursor.getString(observedCursor.getColumnIndexOrThrow("stable_id"))?.trim().orEmpty(),
                                    displayName = observedCursor.getString(observedCursor.getColumnIndexOrThrow("display_name"))?.trim().orEmpty(),
                                    roomName = observedCursor.getString(observedCursor.getColumnIndexOrThrow("room_name"))?.trim().orEmpty(),
                                ),
                            )
                        }
                    }
                }

            matchObservedProfileUserLinks(chatId, observedProfiles, userDisplayNames).forEach { link ->
                db.execSQL(
                    """
                    INSERT OR REPLACE INTO observed_profile_user_links (
                        stable_id,
                        user_id,
                        chat_id,
                        display_name,
                        room_name,
                        updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    arrayOf<Any?>(
                        link.stableId,
                        link.userId,
                        link.chatId,
                        link.displayName,
                        link.roomName,
                        updatedAt,
                    ),
                )
            }
        }
    }

    override fun learnFromTimestampCorrelation(
        chatId: Long,
        userId: Long,
        messageCreatedAtMs: Long,
    ) {
        synchronized(dbLock) {
            db
                .rawQuery(
                    """
                    SELECT 1
                    FROM observed_profile_user_links
                    WHERE user_id = ? AND chat_id = ?
                    LIMIT 1
                    """.trimIndent(),
                    arrayOf(userId.toString(), chatId.toString()),
                ).use { cursor ->
                    if (cursor.moveToFirst()) {
                        return
                    }
                }

            val candidates =
                db
                    .rawQuery(
                        """
                        SELECT op.stable_id, op.display_name, op.room_name
                        FROM observed_profiles op
                        LEFT JOIN observed_profile_user_links opl ON op.stable_id = opl.stable_id
                        WHERE op.chat_id = ?
                          AND op.posted_at BETWEEN ? AND ?
                          AND opl.stable_id IS NULL
                        """.trimIndent(),
                        arrayOf(
                            chatId.toString(),
                            (messageCreatedAtMs - CORRELATION_WINDOW_MS).toString(),
                            (messageCreatedAtMs + CORRELATION_WINDOW_MS).toString(),
                        ),
                    ).use { cursor ->
                        buildList {
                            while (cursor.moveToNext()) {
                                add(
                                    ObservedProfileRecord(
                                        stableId = cursor.getString(cursor.getColumnIndexOrThrow("stable_id"))?.trim().orEmpty(),
                                        displayName = cursor.getString(cursor.getColumnIndexOrThrow("display_name"))?.trim().orEmpty(),
                                        roomName = cursor.getString(cursor.getColumnIndexOrThrow("room_name"))?.trim().orEmpty(),
                                    ),
                                )
                            }
                        }
                    }

            if (candidates.size != 1) {
                return
            }

            val link = candidates.single()

            // 상관 윈도우 내에 다른 발신자의 프로필이 존재하면 매칭 모호 — 스킵
            val nearbyOtherCount =
                db
                    .rawQuery(
                        """
                        SELECT COUNT(*) FROM observed_profiles
                        WHERE chat_id = ?
                          AND posted_at BETWEEN ? AND ?
                          AND stable_id != ?
                        """.trimIndent(),
                        arrayOf(
                            chatId.toString(),
                            (messageCreatedAtMs - CORRELATION_WINDOW_MS).toString(),
                            (messageCreatedAtMs + CORRELATION_WINDOW_MS).toString(),
                            link.stableId,
                        ),
                    ).use { cursor ->
                        if (cursor.moveToFirst()) cursor.getInt(0) else 0
                    }
            if (nearbyOtherCount > 0) {
                return
            }

            db.execSQL(
                """
                INSERT OR REPLACE INTO observed_profile_user_links (
                    stable_id,
                    user_id,
                    chat_id,
                    display_name,
                    room_name,
                    updated_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any?>(
                    link.stableId,
                    userId,
                    chatId,
                    link.displayName,
                    link.roomName,
                    System.currentTimeMillis(),
                ),
            )
        }
    }

    override fun resolveObservedDisplayName(
        userId: Long,
        chatId: Long?,
    ): String? =
        synchronized(dbLock) {
            val (sql, bindArgs) =
                if (chatId != null) {
                    """
                    SELECT display_name
                    FROM observed_profile_user_links
                    WHERE user_id = ? AND chat_id = ?
                    ORDER BY updated_at DESC
                    LIMIT 1
                    """.trimIndent() to arrayOf(userId.toString(), chatId.toString())
                } else {
                    """
                    SELECT display_name
                    FROM observed_profile_user_links
                    WHERE user_id = ?
                    ORDER BY updated_at DESC
                    LIMIT 1
                    """.trimIndent() to arrayOf(userId.toString())
                }

            db.rawQuery(sql, bindArgs).use { cursor ->
                if (!cursor.moveToFirst()) {
                    return@synchronized null
                }
                cursor.getString(cursor.getColumnIndexOrThrow("display_name"))?.takeIf { it.isNotBlank() }
            }
        }

    override fun close() {
        synchronized(dbLock) {
            if (db.isOpen) {
                db.close()
            }
        }
    }

    private fun ensureObservedProfileTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS observed_profiles (
                stable_id TEXT PRIMARY KEY,
                display_name TEXT NOT NULL,
                room_name TEXT NOT NULL,
                chat_id INTEGER,
                notification_key TEXT NOT NULL,
                posted_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        ensureObservedProfileChatIdColumn(db)
        backfillObservedProfileChatIds(db)
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS idx_observed_profiles_chat_id_updated
            ON observed_profiles (chat_id, updated_at DESC)
            """.trimIndent(),
        )
    }

    private fun ensureObservedProfileUserLinkTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS observed_profile_user_links (
                stable_id TEXT PRIMARY KEY,
                user_id INTEGER NOT NULL,
                chat_id INTEGER NOT NULL,
                display_name TEXT NOT NULL,
                room_name TEXT NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS idx_observed_profile_user_links_user_chat
            ON observed_profile_user_links (user_id, chat_id, updated_at DESC)
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS idx_observed_profile_user_links_user
            ON observed_profile_user_links (user_id, updated_at DESC)
            """.trimIndent(),
        )
    }

    private fun migrateUserLinksIfNeeded(db: SQLiteDatabase) {
        val version =
            db.rawQuery("PRAGMA user_version", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) else 0
            }
        if (version < 1) {
            // v1: 오매칭 방지 로직 도입 — 기존 학습 데이터를 리셋하여 잘못된 매핑 제거
            db.execSQL("DELETE FROM observed_profile_user_links")
            db.execSQL("PRAGMA user_version = 1")
        }
    }

    private fun ensureObservedProfileChatIdColumn(db: SQLiteDatabase) {
        val hasChatIdColumn =
            db.rawQuery("PRAGMA table_info(observed_profiles)", null).use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                var found = false
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameIndex) == "chat_id") {
                        found = true
                        break
                    }
                }
                found
            }
        if (!hasChatIdColumn) {
            db.execSQL("ALTER TABLE observed_profiles ADD COLUMN chat_id INTEGER")
        }
    }

    private fun backfillObservedProfileChatIds(db: SQLiteDatabase) {
        val cursor =
            db.rawQuery(
                """
                SELECT stable_id, notification_key
                FROM observed_profiles
                WHERE chat_id IS NULL
                """.trimIndent(),
                null,
            )
        cursor.use { cursor ->
            val stableIdIndex = cursor.getColumnIndexOrThrow("stable_id")
            val notificationKeyIndex = cursor.getColumnIndexOrThrow("notification_key")
            while (cursor.moveToNext()) {
                val stableId = cursor.getString(stableIdIndex) ?: continue
                val notificationKey = cursor.getString(notificationKeyIndex) ?: continue
                val chatId = extractChatIdFromNotificationKey(notificationKey) ?: continue
                db.execSQL(
                    "UPDATE observed_profiles SET chat_id = ? WHERE stable_id = ?",
                    arrayOf<Any?>(chatId, stableId),
                )
            }
        }
    }

    companion object {
        private const val CORRELATION_WINDOW_MS = 5_000L
    }
}
