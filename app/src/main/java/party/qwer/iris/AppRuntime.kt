package party.qwer.iris

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import party.qwer.iris.delivery.webhook.WebhookOutboxDispatcher
import party.qwer.iris.http.ReplyImageIngressPolicy
import party.qwer.iris.http.SseSubscriberPolicy
import party.qwer.iris.nativecore.NativeCoreHolder
import party.qwer.iris.nativecore.NativeCoreRuntime
import party.qwer.iris.persistence.CheckpointJournal
import party.qwer.iris.persistence.LiveRoomMemberPlanStore
import party.qwer.iris.persistence.MemberIdentityStateStore
import party.qwer.iris.persistence.SnapshotStateStore
import party.qwer.iris.persistence.SqliteDriver
import party.qwer.iris.persistence.SseEventStore
import java.util.ArrayDeque

private const val DEFAULT_SSE_BUFFER_SIZE = 100

internal fun createRuntimeSseEventBus(
    store: SseEventStore,
    bufferSize: Int = DEFAULT_SSE_BUFFER_SIZE,
    clock: () -> Long = System::currentTimeMillis,
): SseEventBus =
    SseEventBus(
        policy = SseSubscriberPolicy(bufferCapacity = bufferSize, replayWindowSize = bufferSize),
        clock = clock,
        store = store,
    )

internal class AppRuntime(
    private val runtimeOptions: RuntimeOptions,
    private val notificationReferer: String,
    private val startupSnapshotFactory: (() -> AppRuntimeRunningSnapshot)? = null,
) {
    private sealed interface RuntimeState {
        data object New : RuntimeState

        data object Starting : RuntimeState

        data class Running(
            val snapshot: AppRuntimeRunningSnapshot,
        ) : RuntimeState

        data object Stopping : RuntimeState

        data object Stopped : RuntimeState
    }

    private val stateLock = Any()
    private var state: RuntimeState = RuntimeState.New
    private var stopRequestedWhileStarting = false

    fun start() {
        runBlocking { startSuspend() }
    }

    suspend fun startSuspend() {
        synchronized(stateLock) {
            when (state) {
                RuntimeState.New, RuntimeState.Stopped -> {
                    state = RuntimeState.Starting
                    stopRequestedWhileStarting = false
                }
                RuntimeState.Starting, RuntimeState.Stopping, is RuntimeState.Running -> return
            }
        }

        val snapshot =
            try {
                startupSnapshotFactory?.invoke() ?: assembleStartup().toRunningSnapshot()
            } catch (error: Throwable) {
                synchronized(stateLock) {
                    state = RuntimeState.Stopped
                }
                throw error
            }

        val shouldStopImmediately =
            synchronized(stateLock) {
                state = RuntimeState.Running(snapshot)
                stopRequestedWhileStarting
            }

        if (shouldStopImmediately) {
            stop()
        }
    }

    suspend fun stop() {
        val snapshot =
            synchronized(stateLock) {
                when (val current = state) {
                    RuntimeState.New,
                    RuntimeState.Stopped,
                    -> return

                    RuntimeState.Starting -> {
                        stopRequestedWhileStarting = true
                        return
                    }

                    RuntimeState.Stopping -> return
                    is RuntimeState.Running -> {
                        state = RuntimeState.Stopping
                        current.snapshot
                    }
                }
            }

        withContext(Dispatchers.IO) {
            IrisLogger.info("[AppRuntime] Shutdown signal received, cleaning up...")
            try {
                snapshot.runShutdown()
                IrisLogger.info("[AppRuntime] Cleanup completed")
            } finally {
                synchronized(stateLock) {
                    state = RuntimeState.Stopped
                }
            }
        }
    }

    internal fun lifecycleStateForTest(): String =
        synchronized(stateLock) {
            when (state) {
                RuntimeState.New -> "NEW"
                RuntimeState.Starting -> "STARTING"
                is RuntimeState.Running -> "RUNNING"
                RuntimeState.Stopping -> "STOPPING"
                RuntimeState.Stopped -> "STOPPED"
            }
        }

    private fun assembleStartup(): AppRuntimeStartupAssembly {
        val rollback = StartupRollback()
        val nativeCoreRuntime = NativeCoreRuntime.create()
        NativeCoreHolder.install(nativeCoreRuntime)
        rollback.defer { NativeCoreHolder.resetForTest() }
        val configManager = ConfigManager()
        rollback.defer {
            if (!configManager.saveConfigNow()) {
                IrisLogger.error("[AppRuntime] Failed to save config during startup rollback")
            }
        }
        val bridgeClient = UdsImageBridgeClient()
        val replyImageIngressPolicy = ReplyImageIngressPolicy.fromEnv()
        try {
            val replyService =
                ReplyRuntimeFactory
                    .create(
                        config = configManager,
                        bridgeClient = bridgeClient,
                        imagePolicy = replyImageIngressPolicy.imagePolicy,
                    ).replyService
            runBlocking { replyService.startSuspend() }
            rollback.defer { runBlocking { replyService.shutdownSuspend() } }
            IrisLogger.info("Message sender thread started")

            val storageRuntime = RuntimeBuilders.createStorageRuntime(configManager)
            val kakaoDb = storageRuntime.kakaoDb
            rollback.defer(kakaoDb::closeConnection)
            val memberRepo = storageRuntime.memberRepository

            val persistenceRuntime =
                PersistenceFactory.createSqliteRuntime(
                    driver = PersistenceFactory.openAndroidDriver(),
                )
            val persistenceDriver = persistenceRuntime.driver
            rollback.defer(persistenceDriver::close)
            val webhookOutboxStore = persistenceRuntime.webhookOutboxStore
            val checkpointJournal = persistenceRuntime.checkpointJournal
            val snapshotStateStore = persistenceRuntime.snapshotStateStore
            val memberIdentityStateStore = persistenceRuntime.memberIdentityStateStore
            rollback.defer(snapshotStateStore::close)
            rollback.defer(memberIdentityStateStore::close)
            val webhookOutboxDispatcher = WebhookOutboxDispatcher(configManager, webhookOutboxStore)
            webhookOutboxDispatcher.start()
            rollback.defer(webhookOutboxDispatcher::close)
            val sseEventBus = createRuntimeSseEventBus(store = persistenceRuntime.sseEventStore)
            rollback.defer(sseEventBus::close)
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
                    memberIdentityStateStore = memberIdentityStateStore,
                    liveRoomMemberPlanStore = persistenceRuntime.liveRoomMemberPlanStore,
                    roomEventStore = persistenceRuntime.roomEventStore,
                    snapshotFullReconcileIntervalMs = runtimeOptions.snapshotFullReconcileIntervalMs,
                    missingTombstoneTtlMs = runtimeOptions.snapshotMissingTombstoneTtlMs,
                    roomEventRetentionMs = runtimeOptions.roomEventRetentionMs,
                )
            val snapshotScope = snapshotRuntime.snapshotScope
            rollback.defer(snapshotScope::cancel)
            val observerHelper = snapshotRuntime.observerHelper
            observerHelper.seedSnapshotCache()
            rollback.defer(observerHelper::close)

            val dbObserver = snapshotRuntime.dbObserver
            dbObserver.startPolling()
            rollback.defer(dbObserver::stopPolling)
            IrisLogger.info("DBObserver started")

            val snapshotObserver = snapshotRuntime.snapshotObserver
            snapshotObserver.start()
            rollback.defer(snapshotObserver::stop)
            IrisLogger.info("SnapshotObserver started")

            val memberIdentityObserver = snapshotRuntime.memberIdentityObserver
            memberIdentityObserver.start()
            rollback.defer(memberIdentityObserver::stop)
            IrisLogger.info("MemberIdentityObserver started")

            val kakaoProfileIndexer =
                KakaoProfileIndexer(
                    profileStore = KakaoDbNotificationIdentityStore(kakaoDb),
                )
            kakaoProfileIndexer.launch()
            rollback.defer(kakaoProfileIndexer::stop)
            IrisLogger.info("Kakao profile indexer started")

            val chatRoomRefreshScheduler =
                ChatRoomRefreshScheduler(
                    enabled = runtimeOptions.chatRoomRefreshEnabled,
                    refreshIntervalMs = runtimeOptions.chatRoomRefreshIntervalMs,
                    openDelayMs = runtimeOptions.chatRoomRefreshOpenDelayMs,
                    roomIdProvider = { chatRoomRefreshRoomIds(memberRepo.listRooms()) },
                    chatRoomOpener = bridgeClient::openChatRoom,
                ).also {
                    it.start()
                    rollback.defer(it::stop)
                }

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
            rollback.defer(imageDeleter::stopDeletion)

            val bridgeHealthCache =
                BridgeHealthCache(
                    healthProvider = bridgeClient::queryHealth,
                    refreshIntervalMs = runtimeOptions.bridgeHealthRefreshMs,
                ).also { it.start() }
            rollback.defer(bridgeHealthCache::stop)

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
                        configReadinessProvider = configManager::runtimeConfigReadiness,
                        nativeCoreDiagnosticsProvider = nativeCoreRuntime::diagnostics,
                        replyStatusProvider = replyService::replyStatusOrNull,
                        memberRepo = memberRepo,
                        sseEventBus = sseEventBus,
                        roomEventStore = persistenceRuntime.roomEventStore,
                        chatRoomIntrospectProvider = bridgeClient::inspectChatRoom,
                        chatRoomOpenProvider = bridgeClient::openChatRoom,
                        memberNicknameDiagnosticsProvider = memberIdentityObserver::diagnostics,
                        bindHost = runtimeOptions.bindHost,
                        nettyWorkerThreads = runtimeOptions.httpWorkerThreads,
                    ).also {
                        it.startServer()
                        rollback.defer(it::stopServer)
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
                memberIdentityStateStore = memberIdentityStateStore,
                liveRoomMemberPlanStore = persistenceRuntime.liveRoomMemberPlanStore,
                snapshotScope = snapshotScope,
                observerHelper = observerHelper,
                dbObserver = dbObserver,
                snapshotObserver = snapshotObserver,
                memberIdentityObserver = memberIdentityObserver,
                kakaoProfileIndexer = kakaoProfileIndexer,
                chatRoomRefreshScheduler = chatRoomRefreshScheduler,
                imageDeleter = imageDeleter,
                bridgeHealthCache = bridgeHealthCache,
                irisServer = irisServer,
            )
        } catch (error: Throwable) {
            rollback.run()
            throw error
        }
    }
}

internal class StartupRollback {
    private val actions = ArrayDeque<() -> Unit>()

    fun defer(action: () -> Unit) {
        actions.addFirst(action)
    }

    fun run() {
        actions.forEach { action ->
            runCatching { action() }
                .onFailure { rollbackError ->
                    IrisLogger.error("[AppRuntime] Startup rollback failed: ${rollbackError.message}", rollbackError)
                }
        }
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
    val memberIdentityStateStore: MemberIdentityStateStore,
    val liveRoomMemberPlanStore: LiveRoomMemberPlanStore,
    val snapshotScope: CoroutineScope,
    val observerHelper: ObserverHelper,
    val dbObserver: DBObserver,
    val snapshotObserver: SnapshotObserver,
    val memberIdentityObserver: MemberIdentityObserver,
    val kakaoProfileIndexer: KakaoProfileIndexer,
    val chatRoomRefreshScheduler: ChatRoomRefreshScheduler,
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
                    stopMemberIdentityObserver = memberIdentityObserver::stop,
                    stopProfileIndexer = kakaoProfileIndexer::stop,
                    stopChatRoomRefreshScheduler = chatRoomRefreshScheduler::stop,
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
                    closeMemberIdentityStateStore = memberIdentityStateStore::close,
                    closeLiveRoomMemberPlanStore = liveRoomMemberPlanStore::close,
                    closePersistenceDriver = persistenceDriver::close,
                    closeKakaoDb = kakaoDb::closeConnection,
                ),
        )
}

internal data class AppRuntimeRunningSnapshot(
    val shutdownHooks: RuntimeBuilders.ShutdownHooks,
) {
    fun shutdownPlan(): List<RuntimeBuilders.ShutdownStep> = RuntimeBuilders.buildShutdownPlan(shutdownHooks)

    fun runShutdown() {
        RuntimeBuilders.runShutdownPlan(shutdownPlan())
    }
}
