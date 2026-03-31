package party.qwer.iris

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import party.qwer.iris.delivery.webhook.OutboxRoutingGateway
import party.qwer.iris.ingress.CommandIngressService
import party.qwer.iris.persistence.CheckpointJournal
import party.qwer.iris.persistence.SnapshotStateStore
import party.qwer.iris.persistence.WebhookDeliveryStore
import party.qwer.iris.snapshot.RoomSnapshotReader
import party.qwer.iris.snapshot.SnapshotCommand
import party.qwer.iris.snapshot.SnapshotCoordinator
import party.qwer.iris.snapshot.SnapshotEventEmitter
import party.qwer.iris.storage.ChatId
import party.qwer.iris.storage.RoomDirectoryQueries

internal data class SnapshotRuntime(
    val snapshotScope: CoroutineScope,
    val snapshotCoordinator: SnapshotCoordinator,
    val ingressService: CommandIngressService,
    val observerHelper: ObserverHelper,
    val dbObserver: DBObserver,
    val snapshotObserver: SnapshotObserver,
)

internal object SnapshotRuntimeFactory {
    fun create(
        configManager: ConfigManager,
        kakaoDb: KakaoDB,
        checkpointJournal: CheckpointJournal,
        memberRepository: MemberRepository,
        roomDirectoryQueries: RoomDirectoryQueries,
        webhookOutboxStore: WebhookDeliveryStore,
        sseEventBus: SseEventBus,
        snapshotStateStore: SnapshotStateStore,
        missingTombstoneTtlMs: Long? = null,
    ): SnapshotRuntime {
        val routingGateway = OutboxRoutingGateway(configManager, webhookOutboxStore)
        val roomSnapshotReader =
            createRoomSnapshotReader(
                listRoomChatIds = { roomDirectoryQueries.listAllRoomIds() },
                snapshot = { chatId: ChatId -> memberRepository.snapshot(chatId.value) },
            )
        val snapshotScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val snapshotCoordinator =
            SnapshotCoordinator(
                scope = snapshotScope,
                roomSnapshotReader = roomSnapshotReader,
                diffEngine = RoomSnapshotManager(),
                emitter = SnapshotEventEmitter(sseEventBus, routingGateway),
                stateStore = snapshotStateStore,
            )
        val ingressService =
            CommandIngressService(
                db = kakaoDb,
                config = configManager,
                checkpointJournal = checkpointJournal,
                memberRepo = memberRepository,
                routingGateway = routingGateway,
                learnFromTimestampCorrelation = kakaoDb::learnFromTimestampCorrelation,
                onMarkDirty = { chatId ->
                    snapshotCoordinator.enqueue(SnapshotCommand.MarkDirty(ChatId(chatId)))
                },
            )
        val observerHelper =
            ObserverHelper(
                ingressService = ingressService,
                snapshotCoordinator = snapshotCoordinator,
                checkpointJournal = checkpointJournal,
            )
        return SnapshotRuntime(
            snapshotScope = snapshotScope,
            snapshotCoordinator = snapshotCoordinator,
            ingressService = ingressService,
            observerHelper = observerHelper,
            dbObserver = DBObserver(observerHelper, configManager),
            snapshotObserver = SnapshotObserver(snapshotCoordinator, checkpointJournal, missingTombstoneTtlMs = missingTombstoneTtlMs),
        )
    }

    fun createRoomSnapshotReader(
        listRoomChatIds: () -> List<ChatId>,
        snapshot: (ChatId) -> party.qwer.iris.snapshot.RoomSnapshotReadResult,
    ): RoomSnapshotReader =
        RuntimeBuilders.buildRoomSnapshotReader(
            listRoomChatIds = listRoomChatIds,
            snapshot = snapshot,
        )
}
