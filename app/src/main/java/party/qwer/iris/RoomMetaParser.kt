package party.qwer.iris

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import party.qwer.iris.model.NoticeInfo
import party.qwer.iris.nativecore.NativeCoreHolder

internal class RoomMetaParser(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun parseRoomTitle(meta: String?): String? =
        NativeCoreHolder.current().parseRoomTitleOrFallback(meta) {
            parseRoomTitleKotlin(meta)
        }

    private fun parseRoomTitleKotlin(meta: String?): String? {
        if (meta.isNullOrBlank()) return null
        return try {
            val element = json.parseToJsonElement(meta)
            val candidates =
                when {
                    element is JsonArray -> element
                    element is JsonObject ->
                        element["noticeActivityContents"]?.jsonArray ?: emptyList()
                    else -> emptyList()
                }
            candidates.firstNotNullOfOrNull { candidate ->
                val obj = candidate.jsonObject
                val type = obj["type"]?.jsonPrimitive?.content?.toIntOrNull()
                val content = obj["content"]?.jsonPrimitive?.content?.trim()
                content?.takeIf { type == 3 && it.isNotBlank() }
            }
        } catch (_: Exception) {
            null
        }
    }

    fun parseNotices(meta: String?): List<NoticeInfo> =
        NativeCoreHolder.current().parseNoticesOrFallback(meta) {
            parseNoticesKotlin(meta)
        }

    private fun parseNoticesKotlin(meta: String?): List<NoticeInfo> {
        if (meta.isNullOrBlank()) return emptyList()
        return try {
            val obj = json.parseToJsonElement(meta).jsonObject
            val noticesArray = obj["noticeActivityContents"]?.jsonArray ?: return emptyList()
            noticesArray.mapNotNull { elem ->
                try {
                    val notice = elem.jsonObject
                    NoticeInfo(
                        content = notice["message"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                        authorId = notice["authorId"]?.jsonPrimitive?.long ?: 0L,
                        updatedAt = notice["createdAt"]?.jsonPrimitive?.long ?: 0L,
                    )
                } catch (_: Exception) {
                    null
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
