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

fun decryptRow(inputRow: Map<String, String?>): Map<String, String?> {
    var row = inputRow.toMutableMap()
    try {
        row = decryptMessageFields(row)
        row = decryptProfileFields(row)
    } catch (e: Exception) {
        IrisLogger.error("JSON processing error during decryption: $e")
    }
    return row
}

private fun decryptMessageFields(row: MutableMap<String, String?>): MutableMap<String, String?> {
    if (!row.contains("message") && !row.contains("attachment")) return row
    val vStr = row.getOrDefault("v", "")
    if (vStr?.isNotEmpty() != true) return row
    return try {
        val vJson = JSONObject(vStr)
        val enc = vJson.optInt("enc", 0)
        val userId = row["user_id"]?.toLongOrNull() ?: Configurable.botId
        if (userId > 0L) decryptRowValues(row, enc, userId, MESSAGE_DECRYPT_KEYS) else row
    } catch (e: JSONException) {
        IrisLogger.error("Error parsing 'v' for decryption: $e")
        row
    }
}

private fun decryptProfileFields(row: MutableMap<String, String?>): MutableMap<String, String?> {
    if (!row.contains("enc") || !PROFILE_DECRYPT_KEYWORDS.any { row.contains(it) }) return row
    val botId = Configurable.botId
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
