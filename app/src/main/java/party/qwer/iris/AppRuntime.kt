package party.qwer.iris

import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import party.qwer.iris.delivery.webhook.OutboxRoutingGateway
import party.qwer.iris.delivery.webhook.WebhookOutboxDispatcher
import party.qwer.iris.ingress.CommandIngressService
import party.qwer.iris.persistence.AndroidSqliteDriver
import party.qwer.iris.persistence.BatchedCheckpointJournal
import party.qwer.iris.persistence.CheckpointJournal
import party.qwer.iris.persistence.IrisDatabaseSchema
import party.qwer.iris.persistence.SqliteWebhookDeliveryStore
import party.qwer.iris.persistence.WebhookDeliveryStore
import party.qwer.iris.reply.DispatchScheduler
import party.qwer.iris.reply.MediaPreparationService
import party.qwer.iris.reply.ReplyAdmissionService
import party.qwer.iris.reply.ReplyCommandFactory
import party.qwer.iris.reply.ReplyStatusTracker
import party.qwer.iris.reply.ReplyTransport
import party.qwer.iris.snapshot.RoomSnapshotAssembler
import party.qwer.iris.snapshot.RoomSnapshotReader
import party.qwer.iris.snapshot.SnapshotCommand
import party.qwer.iris.snapshot.SnapshotCoordinator
import party.qwer.iris.snapshot.SnapshotEventEmitter
import party.qwer.iris.storage.KakaoDbSqlClient
import party.qwer.iris.storage.MemberIdentityQueries
import party.qwer.iris.storage.ObservedProfileQueries
import party.qwer.iris.storage.RoomDirectoryQueries
import party.qwer.iris.storage.RoomStatsQueries
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

internal class AppRuntime(
    private val runtimeOptions: RuntimeOptions,
    private val notificationReferer: String,
) {
    private val started = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)

    private lateinit var configManager: ConfigManager
    private lateinit var bridgeClient: UdsImageBridgeClient
    private lateinit var replyService: ReplyService
    private lateinit var kakaoDb: KakaoDB
    private lateinit var memberRepo: MemberRepository
    private lateinit var webhookOutboxStore: WebhookDeliveryStore
    private lateinit var webhookOutboxDispatcher: WebhookOutboxDispatcher
    private lateinit var persistenceDriver: AndroidSqliteDriver
    private lateinit var sseEventBus: SseEventBus
    private lateinit var checkpointJournal: CheckpointJournal
    private lateinit var snapshotCoordinator: SnapshotCoordinator
    private lateinit var ingressService: CommandIngressService
    private lateinit var snapshotScope: CoroutineScope
    private lateinit var observerHelper: ObserverHelper
    private lateinit var dbObserver: DBObserver
    private lateinit var snapshotObserver: SnapshotObserver
    private lateinit var kakaoProfileIndexer: KakaoProfileIndexer
    private lateinit var imageDeleter: ImageDeleter
    private lateinit var bridgeHealthCache: BridgeHealthCache
    private var irisServer: IrisServer? = null

    fun start() {
        if (!started.compareAndSet(false, true)) {
            return
        }
        configManager = ConfigManager()
        bridgeClient = UdsImageBridgeClient()

        val replyCommandFactory = ReplyCommandFactory()
        val replyStatusStore = ReplyStatusStore()
        val replyStatusTracker = ReplyStatusTracker(replyStatusStore)
        val mediaPreparationService =
            MediaPreparationService(
                imageDecoder = ::decodeBase64Image,
                mediaScanner = { file -> broadcastMediaScan(Uri.fromFile(file)) },
                imageDir = File(IRIS_IMAGE_DIR_PATH),
                imageMediaScanEnabled =
                    System
                        .getenv("IRIS_IMAGE_MEDIA_SCAN")
                        ?.trim()
                        ?.lowercase()
                        ?.let { raw -> raw != "0" && raw != "false" && raw != "off" }
                        ?: true,
            )
        val replyTransport =
            ReplyTransport(
                notificationReplySender = { referer, chatId, preparedMessage, threadId, threadScope ->
                    dispatchNotificationReply(
                        startService = { intent -> AndroidHiddenApi.startService(intent) },
                        referer = referer,
                        chatId = chatId,
                        preparedMessage = preparedMessage,
                        threadId = threadId,
                        threadScope = threadScope,
                    )
                },
                sharedTextReplySender = { room, preparedMessage, threadId, threadScope ->
                    dispatchSharedTextReply(
                        startActivityAs = { callerPackage, intent -> AndroidHiddenApi.startActivityAs(callerPackage, intent) },
                        room = room,
                        preparedMessage = preparedMessage,
                        threadId = threadId,
                        threadScope = threadScope,
                    )
                },
                nativeImageReplySender = UdsImageReplySender(bridgeClient),
                mediaPreparationService = mediaPreparationService,
            )
        val dispatchScheduler =
            DispatchScheduler(
                baseIntervalMs = { configManager.messageSendRate },
                jitterMaxMs = { configManager.messageSendJitterMax },
            )
        val replyAdmissionService = ReplyAdmissionService()
        replyService =
            ReplyService(
                admissionService = replyAdmissionService,
                commandFactory = replyCommandFactory,
                mediaPreparationService = mediaPreparationService,
                transport = replyTransport,
                dispatchScheduler = dispatchScheduler,
                statusTracker = replyStatusTracker,
            )
        replyService.start()
        IrisLogger.info("Message sender thread started")

        kakaoDb = KakaoDB(configManager)
        val sqlClient = KakaoDbSqlClient(kakaoDb::executeQuery)
        val roomDirectoryQueries = RoomDirectoryQueries(sqlClient)
        val memberIdentityQueries =
            MemberIdentityQueries(
                sqlClient,
                KakaoDecrypt.Companion::decrypt,
                configManager.botId,
            )
        val observedProfileQueries = ObservedProfileQueries(sqlClient)
        val roomStatsQueries = RoomStatsQueries(sqlClient)
        val roomSnapshotAssembler = RoomSnapshotAssembler
        memberRepo =
            MemberRepository(
                roomDirectory = roomDirectoryQueries,
                memberIdentity = memberIdentityQueries,
                observedProfile = observedProfileQueries,
                roomStats = roomStatsQueries,
                snapshotAssembler = roomSnapshotAssembler,
                decrypt = KakaoDecrypt.Companion::decrypt,
                botId = configManager.botId,
                learnObservedProfileUserMappings = kakaoDb::learnObservedProfileUserMappings,
            )
        val persistenceDbPath = "${PathUtils.getAppPath()}databases/iris.db"
        val persistenceDbFile = File(persistenceDbPath)
        persistenceDbFile.parentFile?.mkdirs()
        persistenceDriver =
            AndroidSqliteDriver(
                SQLiteDatabase.openDatabase(
                    persistenceDbFile.absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY,
                ),
            )
        IrisDatabaseSchema.createWebhookOutboxTable(persistenceDriver)
        webhookOutboxStore = SqliteWebhookDeliveryStore(persistenceDriver)
        webhookOutboxDispatcher = WebhookOutboxDispatcher(configManager, webhookOutboxStore)
        webhookOutboxDispatcher.start()
        sseEventBus = SseEventBus(bufferSize = 100)
        checkpointJournal = BatchedCheckpointJournal(store = FileCheckpointStore())

        val routingGateway = OutboxRoutingGateway(configManager, webhookOutboxStore)
        val roomSnapshotReader =
            object : RoomSnapshotReader {
                override fun listRoomChatIds(): List<Long> = memberRepo.listRooms().rooms.map { it.chatId }

                override fun snapshot(chatId: Long): RoomSnapshotData = memberRepo.snapshot(chatId)
            }
        val snapshotEventEmitter = SnapshotEventEmitter(sseEventBus, routingGateway)
        snapshotScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        snapshotCoordinator =
            SnapshotCoordinator(
                scope = snapshotScope,
                roomSnapshotReader = roomSnapshotReader,
                diffEngine = RoomSnapshotManager(),
                emitter = snapshotEventEmitter,
            )
        ingressService =
            CommandIngressService(
                db = kakaoDb,
                config = configManager,
                checkpointJournal = checkpointJournal,
                memberRepo = memberRepo,
                routingGateway = routingGateway,
                learnFromTimestampCorrelation = kakaoDb::learnFromTimestampCorrelation,
                onMarkDirty = { chatId ->
                    snapshotCoordinator.enqueue(SnapshotCommand.MarkDirty(chatId))
                },
            )
        observerHelper =
            ObserverHelper(
                ingressService = ingressService,
                snapshotCoordinator = snapshotCoordinator,
                checkpointJournal = checkpointJournal,
            )
        observerHelper.seedSnapshotCache()

        dbObserver = DBObserver(observerHelper, configManager)
        dbObserver.startPolling()
        IrisLogger.info("DBObserver started")

        snapshotObserver = SnapshotObserver(snapshotCoordinator, checkpointJournal)
        snapshotObserver.start()
        IrisLogger.info("SnapshotObserver started")

        kakaoProfileIndexer =
            KakaoProfileIndexer(
                profileStore = KakaoDbNotificationIdentityStore(kakaoDb),
            )
        kakaoProfileIndexer.launch()
        IrisLogger.info("Kakao profile indexer started")

        imageDeleter =
            ImageDeleter(
                IRIS_IMAGE_DIR_PATH,
                runtimeOptions.imageDeletionIntervalMs,
                runtimeOptions.imageRetentionMs,
            ).also {
                it.startDeletion()
                IrisLogger.info(
                    "ImageDeleter started (intervalMs=${runtimeOptions.imageDeletionIntervalMs}, " +
                        "retentionMs=${runtimeOptions.imageRetentionMs}).",
                )
            }

        bridgeHealthCache =
            BridgeHealthCache(
                healthProvider = bridgeClient::queryHealth,
                refreshIntervalMs = runtimeOptions.bridgeHealthRefreshMs,
            ).also { it.start() }

        irisServer =
            if (runtimeOptions.disableHttp) {
                IrisLogger.info("[AppRuntime] IRIS_DISABLE_HTTP=1; skipping Iris HTTP server startup")
                null
            } else {
                IrisServer(
                    kakaoDb,
                    configManager,
                    notificationReferer,
                    replyService,
                    bridgeHealthProvider = bridgeHealthCache::current,
                    replyStatusProvider = replyService::replyStatusOrNull,
                    memberRepo = memberRepo,
                    sseEventBus = sseEventBus,
                    bindHost = runtimeOptions.bindHost,
                    nettyWorkerThreads = runtimeOptions.httpWorkerThreads,
                ).also {
                    it.startServer()
                    IrisLogger.info("Iris Server started")
                }
            }
    }

    suspend fun stop() {
        if (!stopped.compareAndSet(false, true)) {
            return
        }
        withContext(Dispatchers.IO) {
            IrisLogger.info("[AppRuntime] Shutdown signal received, cleaning up...")
            irisServer?.stopServer()
            dbObserver.stopPolling()
            snapshotObserver.stop()
            kakaoProfileIndexer.stop()
            imageDeleter.stopDeletion()
            webhookOutboxDispatcher.close()
            observerHelper.close()
            snapshotScope.cancel()
            replyService.shutdown()
            bridgeHealthCache.stop()
            if (!configManager.saveConfigNow()) {
                IrisLogger.error("[AppRuntime] Failed to save config during shutdown")
            }
            kakaoDb.closeConnection()
            IrisLogger.info("[AppRuntime] Cleanup completed")
        }
    }
}
