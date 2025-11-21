// SendMsg : ye-seola/go-kdb
// Kakaodecrypt : jiru/kakaodecrypt
package party.qwer.iris

import java.io.File
import java.util.concurrent.TimeUnit

const val IMAGE_DIR_PATH: String = "/sdcard/Android/data/com.kakao.talk/files"

class Main {
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            try {
                val notificationReferer = readNotificationReferer()

                // UDS bridge support removed: MQTT must use tcp:// broker URLs directly

                Replier.startMessageSender()
                println("Message sender thread started")

                val kakaoDb = KakaoDB()
                val observerHelper = ObserverHelper(kakaoDb, notificationReferer)

                val dbObserver = DBObserver(kakaoDb, observerHelper)
                dbObserver.startPolling()
                println("DBObserver started")

                val imageDeleter = ImageDeleter(IMAGE_DIR_PATH, TimeUnit.HOURS.toMillis(1))
                imageDeleter.startDeletion()
                println("ImageDeleter started, and will delete images older than 1 hour.")

                val disableHttp = System.getenv("IRIS_DISABLE_HTTP")?.equals("1", ignoreCase = true) == true
                if (disableHttp) {
                    println("[Main] IRIS_DISABLE_HTTP=1; skipping Iris HTTP server startup")
                    // Keep process alive and DB open for background workers
                    while (true) Thread.sleep(60_000)
                } else {
                    val irisServer = IrisServer(
                        kakaoDb, dbObserver, observerHelper, notificationReferer
                    )
                    irisServer.startServer()
                    println("Iris Server started")
                    kakaoDb.closeConnection()
                }
            } catch (e: Exception) {
                System.err.println("Iris Error")
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
                        println("Found NotificationReferer in prefs")
                        return refererFromPrefs
                    }
                } catch (_: Exception) {
                    // fall through to env/defaults
                }
            }

            // 2) Allow override via environment variable
            val envReferer = System.getenv("IRIS_NOTIFICATION_REFERER")
            if (!envReferer.isNullOrBlank()) {
                println("Using IRIS_NOTIFICATION_REFERER from environment")
                return envReferer
            }

            // 3) Fallback to a safe default. Note: Some KakaoTalk versions accept a generic referer.
            // If sending replies fails, set IRIS_NOTIFICATION_REFERER explicitly.
            val fallback = "Iris"
            println("NotificationReferer not found in prefs; using fallback '$fallback'. Set IRIS_NOTIFICATION_REFERER to override.")
            return fallback
        }
    }
}
