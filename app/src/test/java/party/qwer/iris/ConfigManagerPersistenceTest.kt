package party.qwer.iris

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConfigManagerPersistenceTest {
    @Test
    fun `saveConfigNow persists config and removes temp file`() {
        val configDir = Files.createTempDirectory("iris-config-manager").toFile()
        val configPath = configDir.resolve("config.json").absolutePath
        val manager = ConfigManager(configPath = configPath)

        manager.botName = "IrisTest"

        assertTrue(manager.saveConfigNow())
        val configFile = configDir.resolve("config.json")
        assertTrue(configFile.exists())
        assertTrue(configDir.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
        assertTrue(configFile.readText().contains("IrisTest"))

        val reloaded = ConfigManager(configPath = configPath)
        assertEquals("IrisTest", reloaded.botName)
        configDir.deleteRecursively()
    }

    @Test
    fun `bot id is not persisted in user config file`() {
        val configDir = Files.createTempDirectory("iris-config-manager-botid").toFile()
        val configPath = configDir.resolve("config.json").absolutePath
        val manager = ConfigManager(configPath = configPath)

        manager.botId = 777L

        assertTrue(manager.saveConfigNow())
        val configText = configDir.resolve("config.json").readText()
        assertTrue(!configText.contains("\"botId\""))
        val reloaded = ConfigManager(configPath = configPath)
        assertEquals(0L, reloaded.botId)
        configDir.deleteRecursively()
    }

    @Test
    fun `signing secret is empty when config has no webhookToken`() {
        val configDir = Files.createTempDirectory("iris-config-manager-signing-secret").toFile()
        val configPath = configDir.resolve("config.json").absolutePath
        val manager = ConfigManager(configPath = configPath)

        assertEquals("", manager.signingSecret())
        configDir.deleteRecursively()
    }

    @Test
    fun `signing secret reads from config file webhookToken`() {
        val configDir = Files.createTempDirectory("iris-config-manager-webhook-secret").toFile()
        val configPath = configDir.resolve("config.json").absolutePath
        configDir.resolve("config.json").writeText("""{"webhookToken":"file-secret"}""")
        val manager = ConfigManager(configPath = configPath)

        assertEquals("file-secret", manager.signingSecret())
        configDir.deleteRecursively()
    }
}
