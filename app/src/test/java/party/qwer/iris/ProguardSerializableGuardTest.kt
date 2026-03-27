package party.qwer.iris

import java.io.File
import kotlin.test.Test
import kotlin.test.fail

/**
 * proguard-rules.pro가 party.qwer.iris.model.** 범위만 keep하므로,
 * 이 패키지 밖에 @Serializable 클래스가 추가되면 R8 release에서 깨집니다.
 *
 * 이 테스트는 소스 트리를 스캔하여 model 패키지 밖의 @Serializable 사용을 차단합니다.
 */
class ProguardSerializableGuardTest {
    private val allowedPaths =
        setOf(
            "app/src/main/java/party/qwer/iris/model/",
            "app/src/main/java/party/qwer/iris/util/",
        )

    @Test
    fun `all Serializable DTOs live in allowed packages`() {
        val appMainDir = findAppMainDir() ?: return

        val violations = mutableListOf<String>()
        appMainDir
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                val relativePath = file.toRelativeString(appMainDir)
                if (allowedPaths.any { "java/$relativePath".startsWith(it.removePrefix("app/src/main/")) }) return@forEach

                val content = file.readText()
                if (content.contains("@Serializable") && !content.contains("@file:Suppress")) {
                    violations.add(relativePath)
                }
            }

        if (violations.isNotEmpty()) {
            fail(
                "Found @Serializable outside allowed packages (proguard-rules.pro won't keep these):\n" +
                    violations.joinToString("\n") { "  - $it" } +
                    "\n\nMove to party.qwer.iris.model or update proguard-rules.pro.",
            )
        }
    }

    private fun findAppMainDir(): File? {
        val candidates =
            listOf(
                File("app/src/main/java"),
                File("../app/src/main/java"),
            )
        return candidates.firstOrNull { it.isDirectory }
    }
}
