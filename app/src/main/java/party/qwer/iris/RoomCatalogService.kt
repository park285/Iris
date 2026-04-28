package party.qwer.iris

import party.qwer.iris.model.BotCommandInfo
import party.qwer.iris.model.OpenLinkInfo
import party.qwer.iris.model.RoomInfoResponse
import party.qwer.iris.model.RoomListResponse
import party.qwer.iris.model.RoomSummary
import party.qwer.iris.storage.ChatId
import party.qwer.iris.storage.RoomDirectoryQueries

internal class RoomCatalogService(
    private val roomDirectory: RoomDirectoryQueries,
    private val metadata: MemberRepositoryMetadata,
) {
    fun listRooms(): RoomListResponse {
        val roomRows = roomDirectory.listAllRooms()
        val parsedRoomTitles = metadata.parseRoomTitles(roomRows.map { row -> row.meta })
        val rooms =
            roomRows.mapIndexed { index, row ->
                RoomSummary(
                    chatId = row.id.value,
                    type = row.type,
                    linkId = row.linkId?.value,
                    activeMembersCount = row.activeMembersCount,
                    linkName =
                        row.linkName
                            ?: metadata.resolveNonOpenRoomName(
                                chatId = row.id,
                                roomType = row.type,
                                meta = row.meta,
                                members = row.members,
                                parsedRoomTitle = parsedRoomTitles.getOrNull(index),
                            ),
                    linkUrl = row.linkUrl,
                    memberLimit = row.memberLimit,
                    searchable = row.searchable,
                    botRole = row.botRole,
                )
            }
        return RoomListResponse(
            rooms =
                rooms
                    .groupBy { it.linkId ?: it.chatId }
                    .values
                    .map { group ->
                        group.maxWithOrNull(
                            compareBy<RoomSummary>(
                                { if (it.chatId > 0) 1 else 0 },
                                { it.activeMembersCount ?: 0 },
                                { it.chatId },
                            ),
                        ) ?: group.first()
                    },
        )
    }

    fun roomSummary(chatId: ChatId): RoomSummary? {
        val roomRow = roomDirectory.findRoomById(chatId) ?: return null
        val openLink = roomRow.linkId?.let(roomDirectory::loadOpenLink)
        return RoomSummary(
            chatId = roomRow.id.value,
            type = roomRow.type,
            linkId = roomRow.linkId?.value,
            activeMembersCount = roomRow.activeMembersCount,
            linkName =
                openLink?.name
                    ?: metadata.resolveNonOpenRoomName(
                        chatId = roomRow.id,
                        roomType = roomRow.type,
                        meta = roomRow.meta,
                        members = roomRow.members,
                    ),
            linkUrl = openLink?.url,
            memberLimit = openLink?.memberLimit,
            searchable = openLink?.searchable,
            botRole = roomRow.botRole,
        )
    }

    fun roomInfo(chatId: ChatId): RoomInfoResponse {
        val roomRow =
            roomDirectory.findRoomForInfo(chatId)
                ?: return RoomInfoResponse(chatId.value, null, null, emptyList(), emptyList(), emptyList())

        val linkId = roomRow.linkId
        val notices = metadata.parseNotices(roomRow.meta)
        val blindedIds = metadata.parseJsonLongArray(roomRow.blindedMemberIds).toList()

        val botCommands =
            if (linkId != null) {
                roomDirectory.loadBotCommands(linkId).map { BotCommandInfo(it.first, it.second) }
            } else {
                emptyList()
            }

        val openLink =
            if (linkId != null) {
                roomDirectory.loadOpenLink(linkId)?.let { row ->
                    OpenLinkInfo(
                        name = row.name,
                        url = row.url,
                        memberLimit = row.memberLimit,
                        description = row.description,
                        searchable = row.searchable,
                    )
                }
            } else {
                null
            }

        return RoomInfoResponse(
            chatId = chatId.value,
            type = roomRow.type,
            linkId = linkId?.value,
            notices = notices,
            blindedMemberIds = blindedIds,
            botCommands = botCommands,
            openLink = openLink,
        )
    }
}
