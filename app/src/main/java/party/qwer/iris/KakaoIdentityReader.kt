package party.qwer.iris

internal class KakaoIdentityReader(
    private val runtime: KakaoDbRuntime,
    private val config: ConfigProvider,
) {
    private val hasOpenChatMember: Boolean by lazy { checkNewDb() }

    fun resolveSenderName(userId: Long): String {
        if (userId == config.botId) {
            return config.botName
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

        return runtime.withReadConnection { db ->
            db.rawQuery(sql, bindArgs).use { cursor ->
                decryptUserName(cursor, userId)
            }
        }
    }

    private fun decryptUserName(
        cursor: android.database.Cursor,
        userId: Long,
    ): String? {
        if (!cursor.moveToNext()) {
            IrisLogger.debugLazy { "[getNameOfUserId] No user found for userId=$userId" }
            return "Unknown"
        }

        val encryptedName = cursor.getString(cursor.getColumnIndexOrThrow("name"))
        val enc = cursor.getInt(cursor.getColumnIndexOrThrow("enc"))
        if (encryptedName == null) {
            IrisLogger.debugLazy { "[getNameOfUserId] name column is null for userId=$userId" }
            return "Unknown"
        }

        val botId = config.botId
        if (botId <= 0L) return encryptedName

        return try {
            KakaoDecrypt.decrypt(enc, encryptedName, botId)
        } catch (e: Exception) {
            IrisLogger.debugLazy { "Decryption error in getNameOfUserId: $e" }
            encryptedName
        }
    }

    private fun checkNewDb(): Boolean =
        runtime.withPrimaryConnection { db ->
            db
                .rawQuery(
                    "SELECT name FROM db2.sqlite_master WHERE type='table' AND name='open_chat_member'",
                    null,
                ).use { cursor ->
                    cursor.moveToFirst()
                }
        }
}
