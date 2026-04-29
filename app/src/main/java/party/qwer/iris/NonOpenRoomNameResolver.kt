package party.qwer.iris

import party.qwer.iris.storage.ChatId
import party.qwer.iris.storage.LinkId
import party.qwer.iris.storage.ObservedProfileQueries
import party.qwer.iris.storage.UserId

internal class NonOpenRoomNameResolver(
    private val roomMetaParser: RoomMetaParser,
    private val observedProfile: ObservedProfileQueries,
    private val resolveNicknamesBatch: (
        userIds: Collection<UserId>,
        linkId: LinkId?,
        chatId: ChatId?,
    ) -> Map<UserId, String>,
    private val jsonIdArrayParser: JsonIdArrayParser,
    private val botId: Long,
) {
    fun resolveObservedRoomName(chatId: ChatId): String? =
        observedProfile
            .resolveProfileByChatId(chatId)
            ?.roomName
            ?.takeIf { it.isNotBlank() }

    fun resolve(
        chatId: ChatId,
        roomType: String?,
        meta: String?,
        members: String?,
        parsedRoomTitle: String? = null,
        parsedRoomTitleKnown: Boolean = false,
        observedRoomName: String? = null,
        observedRoomNameKnown: Boolean = false,
        parsedMemberIds: Set<Long>? = null,
    ): String? {
        val titleFromMeta =
            if (parsedRoomTitleKnown) {
                parsedRoomTitle
            } else {
                parsedRoomTitle ?: roomMetaParser.parseRoomTitle(meta)
            }
        if (!titleFromMeta.isNullOrBlank()) {
            return titleFromMeta
        }

        val resolvedObservedRoomName =
            if (observedRoomNameKnown) {
                observedRoomName
            } else {
                resolveObservedRoomName(chatId)
            }
        if (!resolvedObservedRoomName.isNullOrBlank()) {
            return resolvedObservedRoomName
        }

        val memberIds =
            (parsedMemberIds ?: jsonIdArrayParser.parse(members))
                .filter { it != botId }
                .map(::UserId)
        if (memberIds.isEmpty()) {
            return roomType
        }
        val names =
            resolveNicknamesBatch(memberIds, null, chatId)
                .values
                .filter { it.isNotBlank() }
        if (names.isEmpty()) {
            return roomType
        }
        return if (KakaoRoomType.isDirectChat(roomType)) names.first() else names.joinToString(", ")
    }
}
