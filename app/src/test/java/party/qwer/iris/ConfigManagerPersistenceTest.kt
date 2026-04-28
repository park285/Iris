package party.qwer.iris

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfigManagerPersistenceTest {
    @Test
    fun `saveConfigNow persists config and removes temp file via facade`() {
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
    fun `bot id is not persisted in user config file via facade`() {
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
    fun `inbound signing secret is empty when config has no migrated or explicit secret fields`() {
        val configDir = Files.createTempDirectory("iris-config-manager-signing-secret").toFile()
        val configPath = configDir.resolve("config.json").absolutePath
        val manager = ConfigManager(configPath = configPath)

        assertEquals("", manager.signingSecret())
        assertEquals("", manager.inboundSigningSecret)
        configDir.deleteRecursively()
    }

    @Test
    fun `inbound signing secret reads from explicit PR-0 field`() {
        val configDir = Files.createTempDirectory("iris-config-manager-signing").toFile()
        val configPath = configDir.resolve("config.json").absolutePath
        configDir.resolve("config.json").writeText("""{"inboundSigningSecret":"direct-secret"}""")
        val manager = ConfigManager(configPath = configPath)

        assertEquals("direct-secret", manager.signingSecret())
        assertEquals("direct-secret", manager.inboundSigningSecret)
        configDir.deleteRecursively()
    }

    @Test
    fun `bridge token survives load and save roundtrip`() {
        val configDir = Files.createTempDirectory("iris-config-manager-bridge-token").toFile()
        val configPath = configDir.resolve("config.json").absolutePath
        configDir.resolve("config.json").writeText("""{"bridgeToken":"bridge-secret"}""")

        val manager = ConfigManager(configPath = configPath)

        assertTrue(manager.saveConfigNow())
        val configText = configDir.resolve("config.json").readText()
        assertTrue(configText.contains("bridge-secret"))
        configDir.deleteRecursively()
    }

    @Test
    fun `invalid existing config fails startup instead of regenerating defaults`() {
        val configDir = Files.createTempDirectory("iris-config-manager-invalid").toFile()
        val configPath = configDir.resolve("config.json").absolutePath
        configDir.resolve("config.json").writeText("""{"botHttpPort":70000}""")

        assertFailsWith<IllegalStateException> {
            ConfigManager(configPath = configPath)
        }
        assertFalse(configDir.resolve("config.json").exists())
        assertTrue(configDir.resolve("config.json.bak").exists())
        configDir.deleteRecursively()
    }

    @Test
    fun `missing config keeps routing maps empty after reload via facade`() {
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
    fun `legacy empty routing maps remain empty on save via facade`() {
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

    @Test
    fun `save failure does not change hot applied config`() {
        val configDir = Files.createTempDirectory("iris-config-manager-save-fail-hot").toFile()
        val blockingParent = configDir.resolve("config-parent-blocker")
        blockingParent.writeText("not a directory")
        val configPath = File(blockingParent, "config.json").absolutePath
        val manager = ConfigManager(configPath = configPath)
        val before = manager.configResponse()

        val outcome =
            applyConfigUpdate(
                configManager = manager,
                name = "sendrate",
                request =
                    party.qwer.iris.model
                        .ConfigRequest(rate = 25),
            )

        assertFalse(outcome.persisted)
        val after = manager.configResponse()
        assertEquals(before.user.messageSendRate, after.user.messageSendRate)
        assertEquals(before.applied.messageSendRate, after.applied.messageSendRate)
        configDir.deleteRecursively()
    }

    @Test
    fun `save failure does not change pending restart config`() {
        val configDir = Files.createTempDirectory("iris-config-manager-save-fail-restart").toFile()
        val blockingParent = configDir.resolve("config-parent-blocker")
        blockingParent.writeText("not a directory")
        val configPath = File(blockingParent, "config.json").absolutePath
        val manager = ConfigManager(configPath = configPath)
        val before = manager.configResponse()

        val outcome =
            applyConfigUpdate(
                configManager = manager,
                name = "botport",
                request =
                    party.qwer.iris.model
                        .ConfigRequest(port = 4000),
            )

        assertFalse(outcome.persisted)
        val after = manager.configResponse()
        assertEquals(before.user.botHttpPort, after.user.botHttpPort)
        assertEquals(before.applied.botHttpPort, after.applied.botHttpPort)
        configDir.deleteRecursively()
    }

    @Test
    fun `no-op update reports persisted on clean saved state`() {
        val configDir = Files.createTempDirectory("iris-config-manager-noop-save-skip").toFile()
        val configPath = configDir.resolve("config.json").absolutePath
        val manager = ConfigManager(configPath = configPath)
        val unchangedRate = manager.messageSendRate

        val outcome =
            applyConfigUpdate(
                configManager = manager,
                name = "sendrate",
                request =
                    party.qwer.iris.model
                        .ConfigRequest(rate = unchangedRate),
            )

        assertTrue(outcome.persisted)
        assertEquals(unchangedRate, manager.messageSendRate)
        assertEquals(unchangedRate, outcome.response?.runtimeApplied?.messageSendRate)
        configDir.deleteRecursively()
    }

    @Test
    fun `no-op update persists current snapshot when loaded config is still dirty`() {
        val configDir = Files.createTempDirectory("iris-config-manager-noop-dirty-persist").toFile()
        val configPath = configDir.resolve("config.json")
        configPath.writeText(
            """
            {
              "webhookToken": "legacy-webhook-secret",
              "botToken": "legacy-bot-secret"
            }
            """.trimIndent(),
        )
        val manager = ConfigManager(configPath = configPath.absolutePath)
        val unchangedRate = manager.messageSendRate

        val outcome =
            applyConfigUpdate(
                configManager = manager,
                name = "sendrate",
                request =
                    party.qwer.iris.model
                        .ConfigRequest(rate = unchangedRate),
            )

        val persistedText = configPath.readText()
        assertTrue(outcome.persisted)
        assertTrue(persistedText.contains("\"inboundSigningSecret\":\"legacy-webhook-secret\""))
        assertTrue(persistedText.contains("\"outboundWebhookToken\":\"legacy-webhook-secret\""))
        assertTrue(persistedText.contains("\"botControlToken\":\"legacy-bot-secret\""))
        assertFalse(persistedText.contains("\"webhookToken\""))
        assertFalse(persistedText.contains("\"botToken\""))
        configDir.deleteRecursively()
    }

    @Test
    fun `missing config startup save failure keeps later no-op update unsaved`() {
        val configDir = Files.createTempDirectory("iris-config-manager-missing-startup-fail").toFile()
        val blockingParent = configDir.resolve("config-parent-blocker")
        blockingParent.writeText("not a directory")
        val configPath = File(blockingParent, "config.json").absolutePath
        val manager = ConfigManager(configPath = configPath)
        val unchangedRate = manager.messageSendRate

        val outcome =
            applyConfigUpdate(
                configManager = manager,
                name = "sendrate",
                request =
                    party.qwer.iris.model
                        .ConfigRequest(rate = unchangedRate),
            )

        assertFalse(outcome.persisted)
        assertEquals(unchangedRate, manager.messageSendRate)
        configDir.deleteRecursively()
    }
}
