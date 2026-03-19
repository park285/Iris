// SendMsg : ye-seola/go-kdb
// Kakaodecrypt : jiru/kakaodecrypt
package party.qwer.iris

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

const val IRIS_IMAGE_DIR_PATH: String = "/sdcard/Android/data/com.kakao.talk/files/iris-outbox-images"

class Main {
    companion object {
        private val defaultImageDeletionIntervalMs = TimeUnit.HOURS.toMillis(1)
        private val defaultImageRetentionMs = TimeUnit.DAYS.toMillis(1)

        @JvmStatic
        fun main(args: Array<String>) {
            try {
                val notificationReferer = readNotificationReferer()
                val shutdownLatch = CountDownLatch(1)

                Configurable.onMessageSendRateChanged = { Replier.restartMessageSender() }
                Replier.startMessageSender()
                IrisLogger.info("Message sender thread started")

                val kakaoDb = KakaoDB()
                val observerHelper = ObserverHelper(kakaoDb)

                val dbObserver = DBObserver(observerHelper)
                dbObserver.startPolling()
                IrisLogger.info("DBObserver started")

                val kakaoProfileIndexer =
                    KakaoProfileIndexer(
                        profileStore = KakaoDbNotificationIdentityStore(kakaoDb),
                    )
                kakaoProfileIndexer.launch()
                IrisLogger.info("Kakao profile indexer started")

                val imageDeleter = startImageDeleter()

                val disableHttp = System.getenv("IRIS_DISABLE_HTTP")?.equals("1", ignoreCase = true) == true
                val irisServer =
                    if (disableHttp) {
                        IrisLogger.info("[Main] IRIS_DISABLE_HTTP=1; skipping Iris HTTP server startup")
                        null
                    } else {
                        IrisServer(
                            kakaoDb,
                            notificationReferer,
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
                            Replier.shutdown()
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

        private fun startImageDeleter(): ImageDeleter {
            val intervalMs =
                readPositiveDurationMillis("IRIS_IMAGE_DELETE_INTERVAL_MS", defaultImageDeletionIntervalMs)
            val retentionMs =
                readPositiveDurationMillis("IRIS_IMAGE_RETENTION_MS", defaultImageRetentionMs)
            return ImageDeleter(IRIS_IMAGE_DIR_PATH, intervalMs, retentionMs).also {
                it.startDeletion()
                IrisLogger.info("ImageDeleter started (intervalMs=$intervalMs, retentionMs=$retentionMs).")
            }
        }

        private fun readPositiveDurationMillis(
            envName: String,
            defaultValue: Long,
        ): Long = positiveDurationMillisOrDefault(System.getenv(envName), defaultValue)
    }
}

internal fun positiveDurationMillisOrDefault(
    rawValue: String?,
    defaultValue: Long,
): Long = rawValue?.trim()?.toLongOrNull()?.takeIf { it > 0L } ?: defaultValue
