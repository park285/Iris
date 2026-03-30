package party.qwer.iris

import party.qwer.iris.snapshot.RoomSnapshotAssembler
import party.qwer.iris.snapshot.RoomSnapshotReader
import party.qwer.iris.storage.ChatId
import party.qwer.iris.storage.KakaoDbSqlClient
import party.qwer.iris.storage.MemberIdentityQueries
import party.qwer.iris.storage.ObservedProfileQueries
import party.qwer.iris.storage.RoomDirectoryQueries
import party.qwer.iris.storage.RoomStatsQueries
import party.qwer.iris.storage.ThreadQueries

internal object RuntimeBuilders {
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
        val stopProfileIndexer: () -> Unit = {},
        val stopImageDeleter: () -> Unit = {},
        val closeWebhookOutbox: () -> Unit = {},
        val closeIngress: () -> Unit = {},
        val cancelSnapshotScope: () -> Unit = {},
        val shutdownReplyService: () -> Unit = {},
        val stopBridgeHealthCache: () -> Unit = {},
        val persistConfig: () -> Unit = {},
        val flushCheckpointJournal: () -> Unit = {},
        val closePersistenceDriver: () -> Unit = {},
        val closeKakaoDb: () -> Unit = {},
    )

    fun createStorageRuntime(configManager: ConfigManager): StorageRuntime {
        val kakaoDb = KakaoDB(configManager)
        val sqlClient = KakaoDbSqlClient(kakaoDb::executeQuery)
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
                roomDirectory = roomDirectoryQueries,
                memberIdentity = memberIdentityQueries,
                observedProfile = observedProfileQueries,
                roomStats = roomStatsQueries,
                threadQueries = threadQueries,
                snapshotAssembler = RoomSnapshotAssembler,
                decrypt = KakaoDecrypt.Companion::decrypt,
                botId = configManager.botId,
                learnObservedProfileUserMappings = kakaoDb::learnObservedProfileUserMappings,
            )
        return StorageRuntime(
            kakaoDb = kakaoDb,
            memberRepository = memberRepository,
            roomDirectoryQueries = roomDirectoryQueries,
        )
    }

    fun buildRoomSnapshotReader(
        listRoomChatIds: () -> List<ChatId>,
        snapshot: (ChatId) -> RoomSnapshotData,
    ): RoomSnapshotReader =
        object : RoomSnapshotReader {
            override fun listRoomChatIds(): List<ChatId> = listRoomChatIds()

            override fun snapshot(chatId: ChatId): RoomSnapshotData = snapshot(chatId)
        }

    fun buildShutdownPlan(hooks: ShutdownHooks): List<ShutdownStep> =
        listOf(
            ShutdownStep(name = "stopServer", action = hooks.stopServer),
            ShutdownStep(name = "stopDbObserver", action = hooks.stopDbObserver),
            ShutdownStep(name = "stopSnapshotObserver", action = hooks.stopSnapshotObserver),
            ShutdownStep(name = "stopProfileIndexer", action = hooks.stopProfileIndexer),
            ShutdownStep(name = "stopImageDeleter", action = hooks.stopImageDeleter),
            ShutdownStep(name = "closeWebhookOutbox", action = hooks.closeWebhookOutbox),
            ShutdownStep(name = "closeIngress", action = hooks.closeIngress),
            ShutdownStep(name = "cancelSnapshotScope", action = hooks.cancelSnapshotScope),
            ShutdownStep(name = "shutdownReplyService", action = hooks.shutdownReplyService),
            ShutdownStep(name = "stopBridgeHealthCache", action = hooks.stopBridgeHealthCache),
            ShutdownStep(name = "persistConfig", action = hooks.persistConfig),
            ShutdownStep(name = "flushCheckpointJournal", action = hooks.flushCheckpointJournal),
            ShutdownStep(name = "closePersistenceDriver", action = hooks.closePersistenceDriver),
            ShutdownStep(name = "closeKakaoDb", action = hooks.closeKakaoDb),
        )

    fun runShutdownPlan(plan: List<ShutdownStep>) {
        plan.forEach { step -> step.action() }
    }
}
