// SendMsg : ye-seola/go-kdb
// Kakaodecrypt : jiru/kakaodecrypt
package party.qwer.iris

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

const val IRIS_IMAGE_DIR_PATH: String = "/sdcard/Android/data/com.kakao.talk/files/iris-outbox-images"

class Main {
    companion object {
        internal val defaultImageDeletionIntervalMs = TimeUnit.HOURS.toMillis(1)
        internal val defaultImageRetentionMs = TimeUnit.DAYS.toMillis(1)

        @JvmStatic
        fun main(args: Array<String>) {
            try {
                val runtimeOptions = RuntimeOptions.fromEnv()
                val notificationReferer = readNotificationReferer()
                val shutdownLatch = CountDownLatch(1)
                val configManager = ConfigManager()
                val bridgeClient = UdsImageBridgeClient()

                val replyService = ReplyService(configManager, UdsImageReplySender(bridgeClient))
                replyService.start()
                IrisLogger.info("Message sender thread started")

                val kakaoDb = KakaoDB(configManager)
                val memberRepo =
                    MemberRepository(
                        executeQuery = { sqlQuery, bindArgs, maxRows ->
                            toLegacyQueryRows(kakaoDb.executeQuery(sqlQuery, bindArgs, maxRows))
                        },
                        decrypt = KakaoDecrypt.Companion::decrypt,
                        botId = configManager.botId,
                    )
                val sseEventBus = SseEventBus(bufferSize = 100)
                val snapshotManager = RoomSnapshotManager()
                val observerHelper =
                    ObserverHelper(
                        kakaoDb,
                        configManager,
                        memberRepo = memberRepo,
                        snapshotManager = snapshotManager,
                        sseEventBus = sseEventBus,
                    )

                val dbObserver = DBObserver(observerHelper, configManager)
                dbObserver.startPolling()
                IrisLogger.info("DBObserver started")

                val kakaoProfileIndexer =
                    KakaoProfileIndexer(
                        profileStore = KakaoDbNotificationIdentityStore(kakaoDb),
                    )
                kakaoProfileIndexer.launch()
                IrisLogger.info("Kakao profile indexer started")

                val imageDeleter = startImageDeleter(runtimeOptions)

                val bridgeHealthCache =
                    BridgeHealthCache(
                        healthProvider = bridgeClient::queryHealth,
                        refreshIntervalMs = runtimeOptions.bridgeHealthRefreshMs,
                    ).also { it.start() }
                val disableHttp = runtimeOptions.disableHttp
                val irisServer =
                    if (disableHttp) {
                        IrisLogger.info("[Main] IRIS_DISABLE_HTTP=1; skipping Iris HTTP server startup")
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
                            // chatRoomIntrospectProvider: requires bridge IPC channel (not yet implemented).
                            // ChatRoomIntrospector exists in bridge module but runs inside the KakaoTalk/Xposed process.
                            // Wire this when ImageBridgeServer exposes chat-room introspection over the existing UDS channel.
                        ).also {
                            it.startServer()
                            IrisLogger.info("Iris Server started")
                        }
                    }

                // Graceful Shutdown Hook
                Runtime.getRuntime().addShutdownHook(
                    Thread {
                        try {
                            IrisLogger.info("[Main] Shutdown signal received, cleaning up...")
                            irisServer?.stopServer()
                            dbObserver.stopPolling()
                            kakaoProfileIndexer.stop()
                            imageDeleter.stopDeletion()
                            observerHelper.close()
                            replyService.shutdown()
                            bridgeHealthCache.stop()
                            if (!configManager.saveConfigNow()) {
                                IrisLogger.error("[Main] Failed to save config during shutdown")
                            }
                            kakaoDb.closeConnection()
                            IrisLogger.info("[Main] Cleanup completed")
                        } finally {
                            shutdownLatch.countDown()
                        }
                    },
                )

                // Keep the process alive with proper shutdown support regardless of HTTP enablement.
                shutdownLatch.await()
            } catch (e: Exception) {
                IrisLogger.error("Iris Error: ${e.message}", e)
            }
        }

        private fun readNotificationReferer(): String {
            val appPath = PathUtils.getAppPath()
            val prefsFile = File("${appPath}shared_prefs/KakaoTalk.hw.perferences.xml")

            // 1) Try reading from KakaoTalk preferences (if present)
            if (prefsFile.exists()) {
                try {
                    val data = prefsFile.bufferedReader().use { it.readText() }
                    val regex = Regex("""<string name=\"NotificationReferer\">(.*?)</string>""")
                    val match = regex.find(data)
                    val refererFromPrefs = match?.groups?.get(1)?.value
                    if (!refererFromPrefs.isNullOrBlank()) {
                        IrisLogger.info("Found NotificationReferer in prefs")
                        return refererFromPrefs
                    }
                } catch (_: Exception) {
                    // fall through to env/defaults
                }
            }

            // 2) Allow override via environment variable
            val envReferer = System.getenv("IRIS_NOTIFICATION_REFERER")
            if (!envReferer.isNullOrBlank()) {
                IrisLogger.info("Using IRIS_NOTIFICATION_REFERER from environment")
                return envReferer
            }

            // 3) Fallback to a safe default. Note: Some KakaoTalk versions accept a generic referer.
            // If sending replies fails, set IRIS_NOTIFICATION_REFERER explicitly.
            val fallback = "Iris"
            IrisLogger.info(
                "NotificationReferer not found in prefs; using fallback '$fallback'. Set IRIS_NOTIFICATION_REFERER to override.",
            )
            return fallback
        }

        private fun startImageDeleter(runtimeOptions: RuntimeOptions): ImageDeleter =
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
    }
}

internal fun positiveDurationMillisOrDefault(
    rawValue: String?,
    defaultValue: Long,
): Long = rawValue?.trim()?.toLongOrNull()?.takeIf { it > 0L } ?: defaultValue
