package party.qwer.iris

import java.io.File
import kotlin.test.Test
import kotlin.test.fail

// 이 테스트는 소스 트리를 스캔하여 R8 keep 규칙 밖의 serializable 사용을 차단합니다.
class ProguardSerializableGuardTest {
    private val allowedPaths =
        setOf(
            "app/src/main/java/party/qwer/iris/model/",
            "app/src/main/java/party/qwer/iris/nativecore/",
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
                    "\n\nMove to a package covered by proguard-rules.pro or update the keep rules.",
            )
        }
    }

    @Test
    fun `native core behavior files do not own DTO declarations`() {
        val appMainDir = findAppMainDir() ?: return
        val nativeCoreDir = appMainDir.resolve("party/qwer/iris/nativecore")
        if (!nativeCoreDir.isDirectory) return

        val allowedDeclarationFiles =
            setOf(
                "NativeCoreConfigModels.kt",
                "NativeCoreDiagnostics.kt",
                "NativeDecryptModels.kt",
                "NativeIngressModels.kt",
                "NativeParserModels.kt",
                "NativeRoutingModels.kt",
                "NativeWebhookModels.kt",
            )
        val violations =
            nativeCoreDir
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" && it.name !in allowedDeclarationFiles }
                .flatMap { file ->
                    Regex("""\bdata\s+class\s+(\w+)""")
                        .findAll(file.readText())
                        .map { match -> file.name to match.groupValues[1] }
                        .asIterable()
                }.toList()

        if (violations.isNotEmpty()) {
            fail(
                "Native core behavior files should keep DTO/model declarations in dedicated model/config files:\n" +
                    violations.joinToString("\n") { (fileName, className) -> "  - $fileName::$className" },
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
