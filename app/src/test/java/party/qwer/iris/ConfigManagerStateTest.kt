package party.qwer.iris

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class ConfigManagerStateTest {
    @Test
    fun `immediate apply field changes getter immediately`() {
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
    fun `restart-required field updates snapshot but not applied`() {
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
    fun `botId change does not mark config dirty`() {
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
}
