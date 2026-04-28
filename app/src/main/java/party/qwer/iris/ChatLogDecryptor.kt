package party.qwer.iris

import org.json.JSONException
import org.json.JSONObject

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

fun decryptRow(
    inputRow: Map<String, String?>,
    config: ConfigProvider,
): Map<String, String?> {
    var row = inputRow.toMutableMap()
    try {
        row = decryptMessageFields(row, config)
        row = decryptProfileFields(row, config)
    } catch (e: Exception) {
        IrisLogger.error("JSON processing error during decryption: $e")
    }
    return row
}

private fun decryptMessageFields(
    row: MutableMap<String, String?>,
    config: ConfigProvider,
): MutableMap<String, String?> {
    if (!row.contains("message") && !row.contains("attachment")) return row
    val vStr = row.getOrDefault("v", "")
    if (vStr?.isNotEmpty() != true) return row
    return try {
        val vJson = JSONObject(vStr)
        val enc = vJson.optInt("enc", 0)
        val userId = row["user_id"]?.toLongOrNull() ?: config.botId
        if (userId > 0L) decryptRowValues(row, enc, userId, MESSAGE_DECRYPT_KEYS) else row
    } catch (e: JSONException) {
        IrisLogger.error("Error parsing 'v' for decryption: $e")
        row
    }
}

private fun decryptProfileFields(
    row: MutableMap<String, String?>,
    config: ConfigProvider,
): MutableMap<String, String?> {
    if (!row.contains("enc") || !PROFILE_DECRYPT_KEYWORDS.any { row.contains(it) }) return row
    val botId = config.botId
    if (botId <= 0L) return row
    val enc = row["enc"]?.toIntOrNull() ?: 0
    return decryptRowValues(row, enc, botId, PROFILE_DECRYPT_KEYS)
}

private fun decryptRowValues(
    row: MutableMap<String, String?>,
    enc: Int,
    botId: Long,
    keysToDecrypt: Array<String>,
): MutableMap<String, String?> {
    val candidates =
        keysToDecrypt.mapNotNull { key ->
            if (!row.containsKey(key)) return@mapNotNull null
            val encryptedValue = row[key] ?: return@mapNotNull null
            if (encryptedValue == "{}" || encryptedValue == "[]") return@mapNotNull null
            key to encryptedValue
        }
    if (candidates.isEmpty()) return row

    val batchResult =
        runCatching {
            KakaoDecrypt.decryptBatch(
                candidates.map { (_, encryptedValue) ->
                    KakaoDecryptBatchItem(enc, encryptedValue, botId)
                },
            )
        }.getOrNull()

    if (batchResult != null && batchResult.size == candidates.size) {
        candidates.zip(batchResult).forEach { (candidate, decryptedValue) ->
            row[candidate.first] = decryptedValue
        }
        return row
    }

    for (key in keysToDecrypt) {
        if (!row.containsKey(key)) continue
        val encryptedValue = row[key] ?: continue
        if (encryptedValue == "{}" || encryptedValue == "[]") continue
        try {
            row[key] = KakaoDecrypt.decrypt(enc, encryptedValue, botId)
        } catch (e: Exception) {
            IrisLogger.error("Decryption error for $key: $e")
        }
    }
    return row
}
