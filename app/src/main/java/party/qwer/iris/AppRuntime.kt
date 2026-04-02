package party.qwer.iris

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import party.qwer.iris.delivery.webhook.WebhookOutboxDispatcher
import party.qwer.iris.http.ReplyImageIngressPolicy
import party.qwer.iris.persistence.CheckpointJournal
import party.qwer.iris.persistence.SnapshotStateStore
import party.qwer.iris.persistence.SqliteDriver
import java.util.concurrent.atomic.AtomicBoolean

internal class AppRuntime(
    private val runtimeOptions: RuntimeOptions,
    private val notificationReferer: String,
) {
    private val started = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)
    private var runningSnapshot: AppRuntimeRunningSnapshot? = null

    fun start() {
        if (!started.compareAndSet(false, true)) {
            return
        }
        runningSnapshot = assembleStartup().toRunningSnapshot()
    }

    suspend fun stop() {
        if (!stopped.compareAndSet(false, true)) {
            return
        }
        val snapshot = runningSnapshot ?: return
        withContext(Dispatchers.IO) {
            IrisLogger.info("[AppRuntime] Shutdown signal received, cleaning up...")
            snapshot.runShutdown()
            IrisLogger.info("[AppRuntime] Cleanup completed")
        }
    }

    private fun assembleStartup(): AppRuntimeStartupAssembly {
        val configManager = ConfigManager()
        val bridgeClient = UdsImageBridgeClient()
        val replyImageIngressPolicy = ReplyImageIngressPolicy.fromEnv()

        val replyService =
            ReplyRuntimeFactory
                .create(
                    config = configManager,
                    bridgeClient = bridgeClient,
                    imagePolicy = replyImageIngressPolicy.imagePolicy,
                ).replyService
        runBlocking { replyService.startSuspend() }
        IrisLogger.info("Message sender thread started")

        val storageRuntime = RuntimeBuilders.createStorageRuntime(configManager)
        val kakaoDb = storageRuntime.kakaoDb
        val memberRepo = storageRuntime.memberRepository

        val persistenceRuntime =
            PersistenceFactory.createSqliteRuntime(
                driver = PersistenceFactory.openAndroidDriver(),
            )
        val persistenceDriver = persistenceRuntime.driver
        val webhookOutboxStore = persistenceRuntime.webhookOutboxStore
        val checkpointJournal = persistenceRuntime.checkpointJournal
        val snapshotStateStore = persistenceRuntime.snapshotStateStore
        val webhookOutboxDispatcher = WebhookOutboxDispatcher(configManager, webhookOutboxStore)
        webhookOutboxDispatcher.start()
        val sseEventBus = SseEventBus(bufferSize = 100)
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
        val snapshotScope = snapshotRuntime.snapshotScope
        val observerHelper = snapshotRuntime.observerHelper
        observerHelper.seedSnapshotCache()

        val dbObserver = snapshotRuntime.dbObserver
        dbObserver.startPolling()
        IrisLogger.info("DBObserver started")

        val snapshotObserver = snapshotRuntime.snapshotObserver
        snapshotObserver.start()
        IrisLogger.info("SnapshotObserver started")

        val kakaoProfileIndexer =
            KakaoProfileIndexer(
                profileStore = KakaoDbNotificationIdentityStore(kakaoDb),
            )
        kakaoProfileIndexer.launch()
        IrisLogger.info("Kakao profile indexer started")

        val imageDeleter =
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

        val bridgeHealthCache =
            BridgeHealthCache(
                healthProvider = bridgeClient::queryHealth,
                refreshIntervalMs = runtimeOptions.bridgeHealthRefreshMs,
            ).also { it.start() }

        val irisServer =
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

        return AppRuntimeStartupAssembly(
            configManager = configManager,
            replyService = replyService,
            kakaoDb = kakaoDb,
            webhookOutboxDispatcher = webhookOutboxDispatcher,
            persistenceDriver = persistenceDriver,
            sseEventBus = sseEventBus,
            checkpointJournal = checkpointJournal,
            snapshotStateStore = snapshotStateStore,
            snapshotScope = snapshotScope,
            observerHelper = observerHelper,
            dbObserver = dbObserver,
            snapshotObserver = snapshotObserver,
            kakaoProfileIndexer = kakaoProfileIndexer,
            imageDeleter = imageDeleter,
            bridgeHealthCache = bridgeHealthCache,
            irisServer = irisServer,
        )
    }
}

private data class AppRuntimeStartupAssembly(
    val configManager: ConfigManager,
    val replyService: ReplyService,
    val kakaoDb: KakaoDB,
    val webhookOutboxDispatcher: WebhookOutboxDispatcher,
    val persistenceDriver: SqliteDriver,
    val sseEventBus: SseEventBus,
    val checkpointJournal: CheckpointJournal,
    val snapshotStateStore: SnapshotStateStore,
    val snapshotScope: CoroutineScope,
    val observerHelper: ObserverHelper,
    val dbObserver: DBObserver,
    val snapshotObserver: SnapshotObserver,
    val kakaoProfileIndexer: KakaoProfileIndexer,
    val imageDeleter: ImageDeleter,
    val bridgeHealthCache: BridgeHealthCache,
    val irisServer: IrisServer?,
) {
    fun toRunningSnapshot(): AppRuntimeRunningSnapshot =
        AppRuntimeRunningSnapshot(
            shutdownHooks =
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
        )
}

internal data class AppRuntimeRunningSnapshot(
    val shutdownHooks: RuntimeBuilders.ShutdownHooks,
) {
    fun shutdownPlan(): List<RuntimeBuilders.ShutdownStep> =
        RuntimeBuilders.buildShutdownPlan(shutdownHooks)

    fun runShutdown() {
        RuntimeBuilders.runShutdownPlan(shutdownPlan())
    }
}
