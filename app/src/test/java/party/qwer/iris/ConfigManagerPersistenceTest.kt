package party.qwer.iris

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
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
    fun `signingSecret is empty when config has no secrets`() {
        val configDir = Files.createTempDirectory("iris-config-manager-signing-secret").toFile()
        val configPath = configDir.resolve("config.json").absolutePath
        val manager = ConfigManager(configPath = configPath)

        assertEquals("", manager.signingSecret())
        assertEquals("", manager.inboundSigningSecret)
        configDir.deleteRecursively()
    }

    @Test
    fun `signingSecret reads from inboundSigningSecret field`() {
        val configDir = Files.createTempDirectory("iris-config-manager-signing").toFile()
        val configPath = configDir.resolve("config.json").absolutePath
        configDir.resolve("config.json").writeText("""{"inboundSigningSecret":"direct-secret"}""")
        val manager = ConfigManager(configPath = configPath)

        assertEquals("direct-secret", manager.signingSecret())
        assertEquals("direct-secret", manager.inboundSigningSecret)
        configDir.deleteRecursively()
    }

    @Test
    fun `missing config keeps routing maps empty after reload`() {
        val configDir = Files.createTempDirectory("iris-config-manager-routing-defaults").toFile()
        val configPath = configDir.resolve("config.json").absolutePath
        val manager = ConfigManager(configPath = configPath)

        assertEquals(emptyMap(), manager.commandRoutePrefixes())
        assertEquals(emptyMap(), manager.imageMessageTypeRoutes())
        assertTrue(manager.saveConfigNow())

        val reloaded = ConfigManager(configPath = configPath)
        assertEquals(emptyMap(), reloaded.commandRoutePrefixes())
        assertEquals(emptyMap(), reloaded.imageMessageTypeRoutes())
        configDir.deleteRecursively()
    }

    @Test
    fun `legacy empty routing maps remain empty on save`() {
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

        assertEquals(emptyMap(), manager.commandRoutePrefixes())
        assertEquals(emptyMap(), manager.imageMessageTypeRoutes())
        assertTrue(manager.saveConfigNow())

        val configText = configDir.resolve("config.json").readText()
        val root = Json.parseToJsonElement(configText).jsonObject
        assertTrue(root.getValue("commandRoutePrefixes").jsonObject.isEmpty())
        assertTrue(root.getValue("imageMessageTypeRoutes").jsonObject.isEmpty())

        val reloaded = ConfigManager(configPath = configPath)
        assertEquals(emptyMap(), reloaded.commandRoutePrefixes())
        assertEquals(emptyMap(), reloaded.imageMessageTypeRoutes())
        configDir.deleteRecursively()
    }
}
