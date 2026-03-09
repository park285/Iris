// SendMsg : ye-seola/go-kdb
// Kakaodecrypt : jiru/kakaodecrypt
package party.qwer.iris

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

const val IMAGE_DIR_PATH: String = "/sdcard/Android/data/com.kakao.talk/files"

class Main {
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            try {
                val notificationReferer = readNotificationReferer()

                Replier.startMessageSender()
                IrisLogger.info("Message sender thread started")

                val kakaoDb = KakaoDB()
                val observerHelper = ObserverHelper(kakaoDb)

                val dbObserver = DBObserver(observerHelper)
                dbObserver.startPolling()
                IrisLogger.info("DBObserver started")

                val imageDeleter = ImageDeleter(IMAGE_DIR_PATH, TimeUnit.HOURS.toMillis(1))
                imageDeleter.startDeletion()
                IrisLogger.info("ImageDeleter started, runs every 1 hour, deletes images older than 1 day.")

                // Graceful Shutdown Hook
                val shutdownLatch = CountDownLatch(1)
                Runtime.getRuntime().addShutdownHook(
                    Thread {
                        IrisLogger.info("[Main] Shutdown signal received, cleaning up...")
                        dbObserver.stopPolling()
                        imageDeleter.stopDeletion()
                        observerHelper.close()
                        Replier.shutdown()
                        kakaoDb.closeConnection()
                        IrisLogger.info("[Main] Cleanup completed")
                        shutdownLatch.countDown()
                    },
                )

                val disableHttp = System.getenv("IRIS_DISABLE_HTTP")?.equals("1", ignoreCase = true) == true
                if (disableHttp) {
                    IrisLogger.info("[Main] IRIS_DISABLE_HTTP=1; skipping Iris HTTP server startup")
                    // Keep process alive with proper shutdown support
                    shutdownLatch.await()
                } else {
                    val irisServer =
                        IrisServer(
                            kakaoDb,
                            dbObserver,
                            observerHelper,
                            notificationReferer,
                        )
                    irisServer.startServer()
                    IrisLogger.info("Iris Server started")
                    // Server runs until terminated
                }
            } catch (e: Exception) {
                IrisLogger.error("Iris Error")
                e.printStackTrace()
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
    }
}
