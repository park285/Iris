// 출처: ye-seola/go-kdb
// 출처: jiru/kakaodecrypt
package party.qwer.iris

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.TimeUnit

const val IRIS_IMAGE_DIR_PATH: String = "/sdcard/Android/data/com.kakao.talk/files/iris-outbox-images"

class Main {
    companion object {
        internal val defaultImageDeletionIntervalMs = TimeUnit.HOURS.toMillis(1)
        internal val defaultImageRetentionMs = TimeUnit.DAYS.toMillis(1)

        @JvmStatic
        fun main(args: Array<String>) {
            try {
                runBlocking {
                    val runtime =
                        AppRuntime(
                            runtimeOptions = RuntimeOptions.fromEnv(),
                            notificationReferer = readNotificationReferer(),
                        )
                    runtime.start()
                    Runtime.getRuntime().addShutdownHook(
                        Thread {
                            runBlocking {
                                runtime.stop()
                            }
                        },
                    )
                    try {
                        awaitCancellation()
                    } finally {
                        runtime.stop()
                    }
                }
            } catch (e: Exception) {
                IrisLogger.error("Iris Error: ${e.message}", e)
            }
        }

        private fun readNotificationReferer(): String {
            val appPath = PathUtils.getAppPath()
            val prefsFile = File("${appPath}shared_prefs/KakaoTalk.hw.perferences.xml")

            // 1) KakaoTalk 환경설정에서 읽기 시도
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
                    // 환경변수/기본값으로 진행
                }
            }

            // 2) 환경변수로 재정의 허용
            val envReferer = System.getenv("IRIS_NOTIFICATION_REFERER")
            if (!envReferer.isNullOrBlank()) {
                IrisLogger.info("Using IRIS_NOTIFICATION_REFERER from environment")
                return envReferer
            }

            // 3) 안전한 기본값으로 폴백. 일부 카카오톡 버전은 범용 referer를 허용함.
            // 답장 전송이 실패하면 IRIS_NOTIFICATION_REFERER를 명시적으로 설정할 것.
            val fallback = "Iris"
            IrisLogger.info(
                "NotificationReferer not found in prefs; using fallback '$fallback'. Set IRIS_NOTIFICATION_REFERER to override.",
            )
            return fallback
        }
    }
}

internal fun positiveDurationMillisOrDefault(
    rawValue: String?,
    defaultValue: Long,
): Long = rawValue?.trim()?.toLongOrNull()?.takeIf { it > 0L } ?: defaultValue
