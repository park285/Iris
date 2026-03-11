package party.qwer.iris

import java.io.File

object PathUtils {
    // 경로는 런타임에 변하지 않으므로 lazy 캐싱
    private val cachedAppPath: String by lazy {
        val androidUid = System.getenv("KAKAOTALK_APP_UID") ?: "0"
        val mirrorPath = "/data_mirror/data_ce/null/$androidUid/com.kakao.talk/"
        val defaultPath = "/data/data/com.kakao.talk/"

        when {
            File(mirrorPath).exists() -> mirrorPath.also { IrisLogger.debug("Using mirrorPath: $it") }
            File(defaultPath).exists() -> defaultPath.also { IrisLogger.debug("Using defaultPath: $it") }
            else -> error("KakaoTalk app path not found")
        }
    }

    fun getAppPath(): String = cachedAppPath
}
