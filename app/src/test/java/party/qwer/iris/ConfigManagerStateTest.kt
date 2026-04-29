package party.qwer.iris

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfigManagerStateTest {
    @Test
    fun `immediate-apply field changes getter immediately via facade`() {
        val tmpDir = Files.createTempDirectory("iris-cfg-state").toFile()
        val configPath = tmpDir.resolve("config.json").absolutePath
        val manager = ConfigManager(configPath)

        manager.messageSendRate = 200L
        assertEquals(200L, manager.messageSendRate)

        manager.saveConfigNow()
        val reloaded = ConfigManager(configPath)
        assertEquals(200L, reloaded.messageSendRate)
        tmpDir.deleteRecursively()
    }

    @Test
    fun `restart-required field updates snapshot but not applied via facade`() {
        val tmpDir = Files.createTempDirectory("iris-cfg-state").toFile()
        val configPath = tmpDir.resolve("config.json").absolutePath
        val manager = ConfigManager(configPath)

        val originalPort = manager.botSocketPort
        manager.botSocketPort = 4000
        assertEquals(originalPort, manager.botSocketPort)

        manager.saveConfigNow()
        val reloaded = ConfigManager(configPath)
        assertEquals(4000, reloaded.botSocketPort)
        tmpDir.deleteRecursively()
    }

    @Test
    fun `discovered botId is not persisted through facade`() {
        val tmpDir = Files.createTempDirectory("iris-cfg-state").toFile()
        val configPath = tmpDir.resolve("config.json").absolutePath
        val manager = ConfigManager(configPath)

        manager.botId = 12345L
        assertEquals(12345L, manager.botId)

        manager.saveConfigNow()
        val reloaded = ConfigManager(configPath)
        assertEquals(0L, reloaded.botId)
        tmpDir.deleteRecursively()
    }

    @Test
    fun `runtime readiness defaults bridge required when bridge token is configured`() {
        val tmpDir = Files.createTempDirectory("iris-cfg-state-bridge-default-required").toFile()
        val configPath = tmpDir.resolve("config.json").absolutePath
        tmpDir.resolve("config.json").writeText("""{"bridgeToken":"bridge-secret"}""")

        val manager = ConfigManager(configPath = configPath, env = emptyMap())

        assertTrue(manager.runtimeConfigReadiness().bridgeRequired)
        tmpDir.deleteRecursively()
    }

    @Test
    fun `runtime readiness defaults bridge optional when bridge token is not configured`() {
        val tmpDir = Files.createTempDirectory("iris-cfg-state-bridge-default-optional").toFile()
        val configPath = tmpDir.resolve("config.json").absolutePath

        val manager = ConfigManager(configPath = configPath, env = emptyMap())

        assertFalse(manager.runtimeConfigReadiness().bridgeRequired)
        tmpDir.deleteRecursively()
    }

    @Test
    fun `runtime readiness obeys explicit bridge requirement override`() {
        val tmpDir = Files.createTempDirectory("iris-cfg-state-bridge-explicit").toFile()
        val configPath = tmpDir.resolve("config.json").absolutePath
        tmpDir.resolve("config.json").writeText("""{"bridgeToken":"bridge-secret"}""")

        val explicitRequired = ConfigManager(configPath = configPath, env = mapOf("IRIS_REQUIRE_BRIDGE" to "true"))
        val explicitOptional = ConfigManager(configPath = configPath, env = mapOf("IRIS_REQUIRE_BRIDGE" to "false"))

        assertTrue(explicitRequired.runtimeConfigReadiness().bridgeRequired)
        assertFalse(explicitOptional.runtimeConfigReadiness().bridgeRequired)
        tmpDir.deleteRecursively()
    }

    @Test
    fun `runtime readiness defaults bridge required when env fallback bridge token is configured`() {
        val tmpDir = Files.createTempDirectory("iris-cfg-state-bridge-env-default-required").toFile()
        val configPath = tmpDir.resolve("config.json").absolutePath

        val manager =
            ConfigManager(
                configPath = configPath,
                env = mapOf("IRIS_BRIDGE_TOKEN" to "env-bridge-secret"),
            )

        assertTrue(manager.runtimeConfigReadiness().bridgeRequired)
        tmpDir.deleteRecursively()
    }

    @Test
    fun `runtime readiness falls back to auto when bridge requirement override is malformed`() {
        val tmpDir = Files.createTempDirectory("iris-cfg-state-bridge-malformed").toFile()
        val configPath = tmpDir.resolve("config.json").absolutePath

        val manager =
            ConfigManager(
                configPath = configPath,
                env =
                    mapOf(
                        "IRIS_BRIDGE_TOKEN" to "env-bridge-secret",
                        "IRIS_REQUIRE_BRIDGE" to "definitely-not-a-boolean",
                    ),
            )

        assertTrue(manager.runtimeConfigReadiness().bridgeRequired)
        tmpDir.deleteRecursively()
    }

    @Test
    fun `runtime readiness rejects placeholder env bridge token`() {
        val tmpDir = Files.createTempDirectory("iris-cfg-state-placeholder-env").toFile()
        val configPath = tmpDir.resolve("config.json").absolutePath

        val manager =
            ConfigManager(
                configPath = configPath,
                env = mapOf("IRIS_BRIDGE_TOKEN" to "change-me"),
            )

        val readiness = manager.runtimeConfigReadiness()
        assertFalse(readiness.placeholderValuesAbsent)
        assertEquals("placeholder runtime config value configured", readiness.failureReason())
        tmpDir.deleteRecursively()
    }
}
