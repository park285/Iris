package party.qwer.iris

import party.qwer.iris.model.NoticeInfo
import party.qwer.iris.storage.ChatId
import party.qwer.iris.storage.LinkId
import party.qwer.iris.storage.ObservedProfileQueries
import party.qwer.iris.storage.UserId

internal class MemberRepositoryMetadata(
    observedProfile: ObservedProfileQueries,
    resolveNicknamesBatch: (
        userIds: Collection<UserId>,
        linkId: LinkId?,
        chatId: ChatId?,
    ) -> Map<UserId, String>,
    botId: Long,
    private val jsonIdArrayParser: JsonIdArrayParser = JsonIdArrayParser(),
    private val roomMetaParser: RoomMetaParser = RoomMetaParser(),
) {
    private val nonOpenRoomNameResolver =
        NonOpenRoomNameResolver(
            roomMetaParser = roomMetaParser,
            observedProfile = observedProfile,
            resolveNicknamesBatch = resolveNicknamesBatch,
            jsonIdArrayParser = jsonIdArrayParser,
            botId = botId,
        )

    fun parseJsonLongArray(raw: String?): Set<Long> = jsonIdArrayParser.parse(raw)

    fun parseRoomTitle(meta: String?): String? = roomMetaParser.parseRoomTitle(meta)

    fun parseRoomTitles(metas: List<String?>): List<String?> = roomMetaParser.parseRoomTitles(metas)

    fun parseNotices(meta: String?): List<NoticeInfo> = roomMetaParser.parseNotices(meta)

    fun resolveNonOpenRoomName(
        chatId: ChatId,
        roomType: String?,
        meta: String?,
        members: String?,
        parsedRoomTitle: String? = null,
    ): String? = nonOpenRoomNameResolver.resolve(chatId, roomType, meta, members, parsedRoomTitle)
}
