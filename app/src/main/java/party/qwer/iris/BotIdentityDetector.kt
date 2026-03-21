package party.qwer.iris

import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject

private const val BOT_USER_ID_FALLBACK_SCAN_LIMIT = 200

internal fun detectBotUserId(db: SQLiteDatabase): Long = detectBotUserIdByStringMatch(db).takeIf { it > 0L } ?: detectBotUserIdByJsonFallback(db)

private fun detectBotUserIdByStringMatch(db: SQLiteDatabase): Long =
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
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }

private fun detectBotUserIdByJsonFallback(db: SQLiteDatabase): Long =
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
                if (!metadata.contains("\"isMine\"")) continue
                val isMine =
                    runCatching {
                        JSONObject(metadata).optBoolean("isMine", false)
                    }.getOrDefault(false)
                if (isMine) return@use userId
            }
            0L
        }

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
