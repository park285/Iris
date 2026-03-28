package party.qwer.iris

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import party.qwer.iris.delivery.webhook.FileWebhookOutboxStore
import party.qwer.iris.delivery.webhook.OutboxRoutingGateway
import party.qwer.iris.delivery.webhook.WebhookOutboxDispatcher
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
    private lateinit var webhookOutboxStore: FileWebhookOutboxStore
    private lateinit var webhookOutboxDispatcher: WebhookOutboxDispatcher
    private lateinit var sseEventBus: SseEventBus
    private lateinit var snapshotManager: RoomSnapshotManager
    private lateinit var observerHelper: ObserverHelper
    private lateinit var dbObserver: DBObserver
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

        replyService = ReplyService(configManager, UdsImageReplySender(bridgeClient))
        replyService.start()
        IrisLogger.info("Message sender thread started")

        kakaoDb = KakaoDB(configManager)
        memberRepo =
            MemberRepository(
                executeQuery = { sqlQuery, bindArgs, maxRows ->
                    toLegacyQueryRows(kakaoDb.executeQuery(sqlQuery, bindArgs, maxRows))
                },
                decrypt = KakaoDecrypt.Companion::decrypt,
                botId = configManager.botId,
                learnObservedProfileUserMappings = kakaoDb::learnObservedProfileUserMappings,
            )
        webhookOutboxStore = FileWebhookOutboxStore()
        webhookOutboxDispatcher = WebhookOutboxDispatcher(configManager, webhookOutboxStore)
        webhookOutboxDispatcher.start()
        sseEventBus = SseEventBus(bufferSize = 100)
        snapshotManager = RoomSnapshotManager()
        observerHelper =
            ObserverHelper(
                kakaoDb,
                configManager,
                memberRepo = memberRepo,
                snapshotManager = snapshotManager,
                sseEventBus = sseEventBus,
                routingGateway = OutboxRoutingGateway(configManager, webhookOutboxStore),
            )

        dbObserver = DBObserver(observerHelper, configManager)
        dbObserver.startPolling()
        IrisLogger.info("DBObserver started")

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
            kakaoProfileIndexer.stop()
            imageDeleter.stopDeletion()
            webhookOutboxDispatcher.close()
            observerHelper.close()
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
