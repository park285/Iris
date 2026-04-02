package party.qwer.iris

import kotlinx.serialization.json.Json
import party.qwer.iris.model.MemberActivityResponse
import party.qwer.iris.model.MemberListResponse
import party.qwer.iris.model.RecentMessagesResponse
import party.qwer.iris.model.RoomInfoResponse
import party.qwer.iris.model.RoomListResponse
import party.qwer.iris.model.RoomSummary
import party.qwer.iris.model.StatsResponse
import party.qwer.iris.model.ThreadListResponse
import party.qwer.iris.snapshot.RoomSnapshotAssembler
import party.qwer.iris.snapshot.RoomSnapshotReadResult
import party.qwer.iris.storage.ChatId
import party.qwer.iris.storage.LinkId
import party.qwer.iris.storage.MemberIdentityQueries
import party.qwer.iris.storage.ObservedProfileQueries
import party.qwer.iris.storage.RoomDirectoryQueries
import party.qwer.iris.storage.RoomStatsQueries
import party.qwer.iris.storage.ThreadQueries
import party.qwer.iris.storage.UserId

internal data class MemberRepositoryDependencies(
    val roomDirectory: RoomDirectoryQueries,
    val memberIdentity: MemberIdentityQueries,
    val identityResolver: MemberIdentityResolver,
    val metadata: MemberRepositoryMetadata,
    val roomCatalogService: RoomCatalogService,
    val memberListingService: MemberListingService,
    val roomStatisticsService: RoomStatisticsService,
    val threadListingService: ThreadListingService,
    val snapshotService: RoomSnapshotService,
)

class MemberRepository internal constructor(
    private val dependencies: MemberRepositoryDependencies,
) {
    private val roomDirectory = dependencies.roomDirectory
    private val memberIdentity = dependencies.memberIdentity
    private val identityResolver = dependencies.identityResolver
    private val metadata = dependencies.metadata
    private val roomCatalogService = dependencies.roomCatalogService
    private val memberListingService = dependencies.memberListingService
    private val roomStatisticsService = dependencies.roomStatisticsService
    private val threadListingService = dependencies.threadListingService
    private val snapshotService = dependencies.snapshotService
    private val periodSpecParser = PeriodSpecParser()

    companion object {
        internal val json = Json { ignoreUnknownKeys = true }

        internal val MESSAGE_TYPE_NAMES =
            mapOf(
                "0" to "text",
                "1" to "photo",
                "2" to "video",
                "3" to "voice",
                "12" to "file",
                "20" to "emoticon",
                "26" to "reply",
                "27" to "multi_photo",
            )
    }

    constructor(
        roomDirectory: RoomDirectoryQueries,
        memberIdentity: MemberIdentityQueries,
        observedProfile: ObservedProfileQueries,
        roomStats: RoomStatsQueries,
        threadQueries: ThreadQueries,
        snapshotAssembler: RoomSnapshotAssembler = RoomSnapshotAssembler,
        decrypt: (Int, String, Long) -> String,
        botId: Long,
        learnObservedProfileUserMappings: (Long, Map<Long, String>) -> Unit = { _, _ -> },
    ) : this(
        dependencies =
            RuntimeBuilders.buildMemberRepositoryDependencies(
                roomDirectory = roomDirectory,
                memberIdentity = memberIdentity,
                observedProfile = observedProfile,
                roomStats = roomStats,
                threadQueries = threadQueries,
                snapshotAssembler = snapshotAssembler,
                decrypt = decrypt,
                botId = botId,
                learnObservedProfileUserMappings = learnObservedProfileUserMappings,
            ),
    )

    fun listRooms(): RoomListResponse = roomCatalogService.listRooms()

    fun roomSummary(chatId: Long): RoomSummary? = roomCatalogService.roomSummary(ChatId(chatId))

    fun listMembers(chatId: Long): MemberListResponse = memberListingService.listMembers(ChatId(chatId))

    fun roomInfo(chatId: Long): RoomInfoResponse = roomCatalogService.roomInfo(ChatId(chatId))

    fun resolveDisplayName(
        userId: Long,
        chatId: Long,
        linkId: Long? = resolveLinkId(ChatId(chatId))?.value,
    ): String =
        identityResolver.resolveNickname(
            userId = UserId(userId),
            linkId = linkId?.let(::LinkId),
            chatId = ChatId(chatId),
        ) ?: userId.toString()

    fun roomStats(
        chatId: Long,
        period: String?,
        limit: Int,
        minMessages: Int = 0,
    ): StatsResponse =
        roomStatisticsService.roomStats(
            chatId = ChatId(chatId),
            period = periodSpecParser.parse(period),
            limit = limit,
            minMessages = minMessages,
        )

    fun memberActivity(
        chatId: Long,
        userId: Long,
        period: String?,
    ): MemberActivityResponse =
        roomStatisticsService.memberActivity(
            chatId = ChatId(chatId),
            userId = UserId(userId),
            period = periodSpecParser.parse(period),
        )

    fun listThreads(chatId: Long): ThreadListResponse = threadListingService.listThreads(ChatId(chatId))

    fun listRecentMessages(
        chatId: Long,
        limit: Int = 50,
    ): RecentMessagesResponse = threadListingService.listRecentMessages(ChatId(chatId), limit)

    internal fun resolveNicknamesBatch(
        userIds: Collection<UserId>,
        linkId: LinkId? = null,
        chatId: ChatId? = null,
    ): Map<UserId, String> = identityResolver.resolveNicknamesBatch(userIds, linkId, chatId)

    fun snapshot(chatId: Long): RoomSnapshotReadResult = snapshotService.snapshot(chatId)

    fun parseJsonLongArray(raw: String?): Set<Long> = metadata.parseJsonLongArray(raw)

    fun parsePeriodSeconds(period: String?): Long? = periodSpecParser.toSeconds(periodSpecParser.parse(period))

    fun resolveSenderRole(
        userId: Long,
        linkId: Long?,
    ): Int? {
        if (linkId == null) return null
        return memberIdentity.resolveSenderRole(UserId(userId), LinkId(linkId))
    }

    private fun resolveLinkId(
        chatId: ChatId,
    ): LinkId? = roomDirectory.resolveLinkId(chatId)
}

data class RoomSnapshotData(
    val chatId: ChatId,
    val linkId: LinkId?,
    val memberIds: Set<UserId>,
    val blindedIds: Set<UserId>,
    val nicknames: Map<UserId, String>,
    val roles: Map<UserId, Int>,
    val profileImages: Map<UserId, String>,
)
