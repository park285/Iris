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

    @Test
    fun `missing config seeds routing defaults and preserves them after reload`() {
        val configDir = Files.createTempDirectory("iris-config-manager-routing-defaults").toFile()
        val configPath = configDir.resolve("config.json").absolutePath
        val manager = ConfigManager(configPath = configPath)

        assertEquals(DEFAULT_COMMAND_ROUTE_PREFIXES, manager.commandRoutePrefixes())
        assertEquals(DEFAULT_IMAGE_MESSAGE_TYPE_ROUTES, manager.imageMessageTypeRoutes())
        assertTrue(manager.saveConfigNow())

        val reloaded = ConfigManager(configPath = configPath)
        assertEquals(DEFAULT_COMMAND_ROUTE_PREFIXES, reloaded.commandRoutePrefixes())
        assertEquals(DEFAULT_IMAGE_MESSAGE_TYPE_ROUTES, reloaded.imageMessageTypeRoutes())
        configDir.deleteRecursively()
    }

    @Test
    fun `legacy empty routing maps are migrated and persisted on save`() {
        val configDir = Files.createTempDirectory("iris-config-manager-routing-migration").toFile()
        val configPath = configDir.resolve("config.json").absolutePath
        configDir.resolve("config.json").writeText(
            """
            {
              "endpoint": "http://example",
              "commandRoutePrefixes": {},
              "imageMessageTypeRoutes": {}
            }
            """.trimIndent(),
        )

        val manager = ConfigManager(configPath = configPath)

        assertEquals(DEFAULT_COMMAND_ROUTE_PREFIXES, manager.commandRoutePrefixes())
        assertEquals(DEFAULT_IMAGE_MESSAGE_TYPE_ROUTES, manager.imageMessageTypeRoutes())
        assertTrue(manager.saveConfigNow())

        val configText = configDir.resolve("config.json").readText()
        assertTrue(configText.contains("!정산"))
        assertTrue(configText.contains("\"chatbotgo\""))

        val reloaded = ConfigManager(configPath = configPath)
        assertEquals(DEFAULT_COMMAND_ROUTE_PREFIXES, reloaded.commandRoutePrefixes())
        assertEquals(DEFAULT_IMAGE_MESSAGE_TYPE_ROUTES, reloaded.imageMessageTypeRoutes())
        configDir.deleteRecursively()
    }
}
