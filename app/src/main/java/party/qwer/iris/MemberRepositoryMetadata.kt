package party.qwer.iris

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import party.qwer.iris.model.NoticeInfo
import party.qwer.iris.storage.ChatId
import party.qwer.iris.storage.LinkId
import party.qwer.iris.storage.ObservedProfileQueries
import party.qwer.iris.storage.UserId

internal class MemberRepositoryMetadata(
    private val observedProfile: ObservedProfileQueries,
    private val resolveNicknamesBatch: (
        userIds: Collection<UserId>,
        linkId: LinkId?,
        chatId: ChatId?,
    ) -> Map<UserId, String>,
    private val botId: Long,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun parseJsonLongArray(raw: String?): Set<Long> {
        if (raw.isNullOrBlank() || raw == "[]") return emptySet()
        return try {
            json
                .parseToJsonElement(raw)
                .jsonArray
                .mapNotNull { it.jsonPrimitive.long }
                .toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    fun parsePeriodSeconds(period: String?): Long? =
        when {
            period == "all" -> null
            period != null && period.endsWith("d") ->
                period.dropLast(1).toLongOrNull()?.times(86400) ?: (7 * 86400L)
            else -> 7 * 86400L
        }

    fun parseRoomTitle(meta: String?): String? {
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

    fun parseNotices(meta: String?): List<NoticeInfo> {
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

    fun resolveNonOpenRoomName(
        chatId: Long,
        roomType: String?,
        meta: String?,
        members: String?,
    ): String? {
        val titleFromMeta = parseRoomTitle(meta)
        if (!titleFromMeta.isNullOrBlank()) {
            return titleFromMeta
        }

        val observedRoomName = observedProfile.resolveProfileByChatId(ChatId(chatId))?.roomName
        if (!observedRoomName.isNullOrBlank()) {
            return observedRoomName
        }

        val memberIds =
            parseJsonLongArray(members)
                .filter { it != botId }
                .map(::UserId)
        if (memberIds.isEmpty()) {
            return roomType
        }
        val names =
            resolveNicknamesBatch(memberIds, null, ChatId(chatId))
                .values
                .filter { it.isNotBlank() }
        if (names.isEmpty()) {
            return roomType
        }
        return if (roomType == "DirectChat") names.first() else names.joinToString(", ")
    }
}
