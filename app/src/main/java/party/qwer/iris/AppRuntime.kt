package party.qwer.iris

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import party.qwer.iris.delivery.webhook.WebhookOutboxDispatcher
import party.qwer.iris.http.ReplyImageIngressPolicy
import party.qwer.iris.ingress.CommandIngressService
import party.qwer.iris.persistence.CheckpointJournal
import party.qwer.iris.persistence.SnapshotStateStore
import party.qwer.iris.persistence.SqliteDriver
import party.qwer.iris.persistence.WebhookDeliveryStore
import party.qwer.iris.snapshot.SnapshotCoordinator
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
    private lateinit var persistenceDriver: SqliteDriver
    private lateinit var sseEventBus: SseEventBus
    private lateinit var checkpointJournal: CheckpointJournal
    private lateinit var snapshotStateStore: SnapshotStateStore
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
        val replyImageIngressPolicy = ReplyImageIngressPolicy.fromEnv()

        replyService =
            ReplyRuntimeFactory
                .create(
                    config = configManager,
                    bridgeClient = bridgeClient,
                    imagePolicy = replyImageIngressPolicy.imagePolicy,
                ).replyService
        runBlocking { replyService.startSuspend() }
        IrisLogger.info("Message sender thread started")

        val storageRuntime = RuntimeBuilders.createStorageRuntime(configManager)
        kakaoDb = storageRuntime.kakaoDb
        memberRepo = storageRuntime.memberRepository

        val persistenceRuntime =
            PersistenceFactory.createSqliteRuntime(
                driver = PersistenceFactory.openAndroidDriver(),
            )
        persistenceDriver = persistenceRuntime.driver
        webhookOutboxStore = persistenceRuntime.webhookOutboxStore
        checkpointJournal = persistenceRuntime.checkpointJournal
        snapshotStateStore = persistenceRuntime.snapshotStateStore
        webhookOutboxDispatcher = WebhookOutboxDispatcher(configManager, webhookOutboxStore)
        webhookOutboxDispatcher.start()
        sseEventBus = SseEventBus(bufferSize = 100)
        val snapshotRuntime =
            SnapshotRuntimeFactory.create(
                configManager = configManager,
                kakaoDb = kakaoDb,
                checkpointJournal = checkpointJournal,
                memberRepository = memberRepo,
                roomDirectoryQueries = storageRuntime.roomDirectoryQueries,
                webhookOutboxStore = webhookOutboxStore,
                sseEventBus = sseEventBus,
                snapshotStateStore = snapshotStateStore,
                roomEventStore = persistenceRuntime.roomEventStore,
                missingTombstoneTtlMs = runtimeOptions.snapshotMissingTombstoneTtlMs,
            )
        snapshotScope = snapshotRuntime.snapshotScope
        snapshotCoordinator = snapshotRuntime.snapshotCoordinator
        ingressService = snapshotRuntime.ingressService
        observerHelper = snapshotRuntime.observerHelper
        observerHelper.seedSnapshotCache()

        dbObserver = snapshotRuntime.dbObserver
        dbObserver.startPolling()
        IrisLogger.info("DBObserver started")

        snapshotObserver = snapshotRuntime.snapshotObserver
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
                    configManager,
                    notificationReferer,
                    replyService,
                    replyImageIngressPolicy = replyImageIngressPolicy,
                    bridgeHealthProvider = bridgeHealthCache::current,
                    replyStatusProvider = replyService::replyStatusOrNull,
                    memberRepo = memberRepo,
                    sseEventBus = sseEventBus,
                    roomEventStore = persistenceRuntime.roomEventStore,
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
            RuntimeBuilders.runShutdownPlan(
                RuntimeBuilders.buildShutdownPlan(
                    RuntimeBuilders.ShutdownHooks(
                        stopServer = { irisServer?.stopServer() },
                        stopDbObserver = dbObserver::stopPolling,
                        stopSnapshotObserver = snapshotObserver::stop,
                        stopProfileIndexer = kakaoProfileIndexer::stop,
                        stopImageDeleter = imageDeleter::stopDeletion,
                        closeWebhookOutbox = webhookOutboxDispatcher::close,
                        closeIngress = observerHelper::close,
                        closeSseEventBus = sseEventBus::close,
                        cancelSnapshotScope = snapshotScope::cancel,
                        shutdownReplyService = { runBlocking { replyService.shutdownSuspend() } },
                        stopBridgeHealthCache = bridgeHealthCache::stop,
                        persistConfig = {
                            if (!configManager.saveConfigNow()) {
                                IrisLogger.error("[AppRuntime] Failed to save config during shutdown")
                            }
                        },
                        flushCheckpointJournal = checkpointJournal::flushNow,
                        closeSnapshotStateStore = snapshotStateStore::close,
                        closePersistenceDriver = persistenceDriver::close,
                        closeKakaoDb = kakaoDb::closeConnection,
                    ),
                ),
            )
            IrisLogger.info("[AppRuntime] Cleanup completed")
        }
    }
}
