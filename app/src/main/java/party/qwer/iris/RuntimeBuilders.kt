package party.qwer.iris

import party.qwer.iris.snapshot.RoomSnapshotAssembler
import party.qwer.iris.snapshot.RoomSnapshotReadResult
import party.qwer.iris.snapshot.RoomSnapshotReader
import party.qwer.iris.storage.ChatId
import party.qwer.iris.storage.KakaoDbSqlClient
import party.qwer.iris.storage.MemberIdentityQueries
import party.qwer.iris.storage.ObservedProfileQueries
import party.qwer.iris.storage.RoomDirectoryQueries
import party.qwer.iris.storage.RoomStatsQueries
import party.qwer.iris.storage.ThreadQueries

internal object RuntimeBuilders {
    internal class ShutdownPlanException(
        failures: List<Pair<String, Throwable>>,
    ) : IllegalStateException(
            "shutdown plan failed for ${failures.size} step(s): ${failures.joinToString { it.first }}",
        ) {
        init {
            failures.forEach { (_, error) -> addSuppressed(error) }
        }
    }

    data class StorageRuntime(
        val kakaoDb: KakaoDB,
        val memberRepository: MemberRepository,
        val roomDirectoryQueries: RoomDirectoryQueries,
    )

    data class ShutdownStep(
        val name: String,
        val action: () -> Unit,
    )

    data class ShutdownHooks(
        val stopServer: () -> Unit = {},
        val stopDbObserver: () -> Unit = {},
        val stopSnapshotObserver: () -> Unit = {},
        val stopMemberIdentityObserver: () -> Unit = {},
        val stopProfileIndexer: () -> Unit = {},
        val stopChatRoomRefreshScheduler: () -> Unit = {},
        val stopImageDeleter: () -> Unit = {},
        val closeWebhookOutbox: () -> Unit = {},
        val closeIngress: () -> Unit = {},
        val closeSseEventBus: () -> Unit = {},
        val cancelSnapshotScope: () -> Unit = {},
        val shutdownReplyService: () -> Unit = {},
        val stopBridgeHealthCache: () -> Unit = {},
        val persistConfig: () -> Unit = {},
        val flushCheckpointJournal: () -> Unit = {},
        val closeSnapshotStateStore: () -> Unit = {},
        val closeMemberIdentityStateStore: () -> Unit = {},
        val closeLiveRoomMemberPlanStore: () -> Unit = {},
        val closePersistenceDriver: () -> Unit = {},
        val closeKakaoDb: () -> Unit = {},
    )

    fun createStorageRuntime(configManager: ConfigManager): StorageRuntime {
        val kakaoDb = KakaoDB(configManager)
        val sqlClient = KakaoDbSqlClient(kakaoDb::executeTypedQuery)
        val roomDirectoryQueries = RoomDirectoryQueries(sqlClient)
        val memberIdentityQueries =
            MemberIdentityQueries(
                db = sqlClient,
                decrypt = KakaoDecrypt.Companion::decrypt,
                botId = configManager.botId,
            )
        val observedProfileQueries = ObservedProfileQueries(sqlClient)
        val roomStatsQueries = RoomStatsQueries(sqlClient)
        val threadQueries = ThreadQueries(sqlClient)
        val memberRepository =
            MemberRepository(
                dependencies =
                    buildMemberRepositoryDependencies(
                        roomDirectory = roomDirectoryQueries,
                        memberIdentity = memberIdentityQueries,
                        observedProfile = observedProfileQueries,
                        roomStats = roomStatsQueries,
                        threadQueries = threadQueries,
                        snapshotAssembler = RoomSnapshotAssembler,
                        decrypt = KakaoDecrypt.Companion::decrypt,
                        botId = configManager.botId,
                        learnObservedProfileUserMappings = kakaoDb::learnObservedProfileUserMappings,
                    ),
            )
        return StorageRuntime(
            kakaoDb = kakaoDb,
            memberRepository = memberRepository,
            roomDirectoryQueries = roomDirectoryQueries,
        )
    }

    internal fun buildMemberRepositoryDependencies(
        roomDirectory: RoomDirectoryQueries,
        memberIdentity: MemberIdentityQueries,
        observedProfile: ObservedProfileQueries,
        roomStats: RoomStatsQueries,
        threadQueries: ThreadQueries,
        snapshotAssembler: RoomSnapshotAssembler,
        decrypt: (Int, String, Long) -> String,
        botId: Long,
        learnObservedProfileUserMappings: (Long, Map<Long, String>) -> Unit,
    ): MemberRepositoryDependencies {
        val memberActivityLookup = MemberActivityLookup(roomStats)
        val identityResolver =
            MemberIdentityResolver(
                memberIdentity = memberIdentity,
                observedProfile = observedProfile,
            )
        val observedProfileLearningService =
            ObservedProfileLearningService(
                identityResolver = identityResolver,
                learnObservedProfileUserMappings = learnObservedProfileUserMappings,
                botId = botId,
            )
        val metadata =
            MemberRepositoryMetadata(
                observedProfile = observedProfile,
                resolveNicknamesBatch = { userIds, linkId, chatId -> identityResolver.resolveNicknamesBatch(userIds, linkId, chatId) },
                botId = botId,
            )
        return MemberRepositoryDependencies(
            roomDirectory = roomDirectory,
            memberIdentity = memberIdentity,
            identityResolver = identityResolver,
            metadata = metadata,
            roomCatalogService =
                RoomCatalogService(
                    roomDirectory = roomDirectory,
                    metadata = metadata,
                ),
            memberListingService =
                MemberListingService(
                    roomDirectory = roomDirectory,
                    memberIdentity = memberIdentity,
                    observedProfile = observedProfile,
                    roomStats = roomStats,
                    memberActivityLookup = memberActivityLookup,
                    parseJsonLongArray = metadata::parseJsonLongArray,
                    prepareNicknameLookup = { userIds, linkId, chatId -> identityResolver.prepareNicknameLookup(userIds, linkId, chatId) },
                    learnObservedProfileMappings = observedProfileLearningService::learnMemberMappings,
                    botId = botId,
                ),
            roomStatisticsService =
                RoomStatisticsService(
                    roomStats = roomStats,
                    resolveLinkId = roomDirectory::resolveLinkId,
                    prepareNicknameLookup = { userIds, linkId, chatId -> identityResolver.prepareNicknameLookup(userIds, linkId, chatId) },
                    resolveNickname = { userId, linkId, chatId -> identityResolver.resolveNickname(userId, linkId, chatId) },
                    messageTypeNames = MemberRepository.MESSAGE_TYPE_NAMES,
                ),
            threadListingService =
                ThreadListingService(
                    roomDirectory = roomDirectory,
                    threadQueries = threadQueries,
                    decrypt = decrypt,
                    botId = botId,
                ),
            snapshotService =
                RoomSnapshotService(
                    roomDirectory = roomDirectory,
                    memberIdentity = memberIdentity,
                    snapshotAssembler = snapshotAssembler,
                    parseJsonLongArray = metadata::parseJsonLongArray,
                    resolveNicknamesBatch = { userIds, linkId, chatId -> identityResolver.resolveNicknamesBatch(userIds, linkId, chatId) },
                    excludeFriendResolvedUsers = identityResolver::excludeFriendResolvedUsers,
                    learnObservedProfileUserMappings = observedProfileLearningService::learnLongNicknames,
                    decrypt = decrypt,
                    botId = botId,
                ),
        )
    }

    fun buildRoomSnapshotReader(
        listRoomChatIds: () -> List<ChatId>,
        snapshot: (ChatId) -> RoomSnapshotReadResult,
    ): RoomSnapshotReader =
        object : RoomSnapshotReader {
            override fun listRoomChatIds(): List<ChatId> = listRoomChatIds()

            override fun snapshot(chatId: ChatId): RoomSnapshotReadResult = snapshot(chatId)
        }

    fun buildShutdownPlan(hooks: ShutdownHooks): List<ShutdownStep> =
        listOf(
            ShutdownStep(name = "stopServer", action = hooks.stopServer),
            ShutdownStep(name = "stopDbObserver", action = hooks.stopDbObserver),
            ShutdownStep(name = "stopSnapshotObserver", action = hooks.stopSnapshotObserver),
            ShutdownStep(name = "stopMemberIdentityObserver", action = hooks.stopMemberIdentityObserver),
            ShutdownStep(name = "stopProfileIndexer", action = hooks.stopProfileIndexer),
            ShutdownStep(name = "stopChatRoomRefreshScheduler", action = hooks.stopChatRoomRefreshScheduler),
            ShutdownStep(name = "stopImageDeleter", action = hooks.stopImageDeleter),
            ShutdownStep(name = "closeWebhookOutbox", action = hooks.closeWebhookOutbox),
            ShutdownStep(name = "closeIngress", action = hooks.closeIngress),
            ShutdownStep(name = "closeSseEventBus", action = hooks.closeSseEventBus),
            ShutdownStep(name = "cancelSnapshotScope", action = hooks.cancelSnapshotScope),
            ShutdownStep(name = "shutdownReplyService", action = hooks.shutdownReplyService),
            ShutdownStep(name = "stopBridgeHealthCache", action = hooks.stopBridgeHealthCache),
            ShutdownStep(name = "persistConfig", action = hooks.persistConfig),
            ShutdownStep(name = "flushCheckpointJournal", action = hooks.flushCheckpointJournal),
            ShutdownStep(name = "closeSnapshotStateStore", action = hooks.closeSnapshotStateStore),
            ShutdownStep(name = "closeMemberIdentityStateStore", action = hooks.closeMemberIdentityStateStore),
            ShutdownStep(name = "closeLiveRoomMemberPlanStore", action = hooks.closeLiveRoomMemberPlanStore),
            ShutdownStep(name = "closePersistenceDriver", action = hooks.closePersistenceDriver),
            ShutdownStep(name = "closeKakaoDb", action = hooks.closeKakaoDb),
        )

    fun runShutdownPlan(plan: List<ShutdownStep>) {
        val failures = mutableListOf<Pair<String, Throwable>>()
        plan.forEach { step ->
            runCatching { step.action() }
                .onFailure { error ->
                    failures += step.name to error
                    IrisLogger.error("[AppRuntime] Shutdown step ${step.name} failed: ${error.message}", error)
                }
        }
        if (failures.isNotEmpty()) {
            throw ShutdownPlanException(failures)
        }
    }
}
