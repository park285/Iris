package party.qwer.iris

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class SecretRoleMigrationTest {
    @Test
    fun `legacy webhookToken seeds inboundSigningSecret and outboundWebhookToken`() {
        val tmpDir = Files.createTempDirectory("iris-secret-migration").toFile()
        val configPath = tmpDir.resolve("config.json").absolutePath
        tmpDir.resolve("config.json").writeText(
            """{"webhookToken":"legacy-wh-secret","botToken":"legacy-bot-secret"}""",
        )

        val manager = ConfigManager(configPath = configPath)

        assertEquals("legacy-wh-secret", manager.inboundSigningSecret)
        assertEquals("legacy-wh-secret", manager.outboundWebhookToken)
        assertEquals("legacy-bot-secret", manager.botControlToken)
        tmpDir.deleteRecursively()
    }

    @Test
    fun `new role fields take precedence over legacy fields`() {
        val tmpDir = Files.createTempDirectory("iris-secret-precedence").toFile()
        val configPath = tmpDir.resolve("config.json").absolutePath
        tmpDir.resolve("config.json").writeText(
            """{
                "webhookToken":"old-wh",
                "botToken":"old-bot",
                "inboundSigningSecret":"new-signing",
                "outboundWebhookToken":"new-outbound",
                "botControlToken":"new-control"
            }""",
        )

        val manager = ConfigManager(configPath = configPath)

        assertEquals("new-signing", manager.inboundSigningSecret)
        assertEquals("new-outbound", manager.outboundWebhookToken)
        assertEquals("new-control", manager.botControlToken)
        tmpDir.deleteRecursively()
    }

    @Test
    fun `save persists new role fields and preserves legacy for rollback`() {
        val tmpDir = Files.createTempDirectory("iris-secret-save").toFile()
        val configPath = tmpDir.resolve("config.json").absolutePath
        tmpDir.resolve("config.json").writeText(
            """{"webhookToken":"legacy-secret","botToken":"legacy-bot"}""",
        )

        val manager = ConfigManager(configPath = configPath)
        manager.saveConfigNow()

        val reloaded = ConfigManager(configPath = configPath)
        assertEquals("legacy-secret", reloaded.inboundSigningSecret)
        assertEquals("legacy-secret", reloaded.outboundWebhookToken)
        assertEquals("legacy-bot", reloaded.botControlToken)
        tmpDir.deleteRecursively()
    }

    @Test
    fun `empty config has empty role fields`() {
        val tmpDir = Files.createTempDirectory("iris-secret-empty").toFile()
        val configPath = tmpDir.resolve("config.json").absolutePath
        val manager = ConfigManager(configPath = configPath)

        assertEquals("", manager.inboundSigningSecret)
        assertEquals("", manager.outboundWebhookToken)
        assertEquals("", manager.botControlToken)
        tmpDir.deleteRecursively()
    }

    @Test
    fun `signingSecret returns inboundSigningSecret`() {
        val tmpDir = Files.createTempDirectory("iris-secret-signing").toFile()
        val configPath = tmpDir.resolve("config.json").absolutePath
        tmpDir.resolve("config.json").writeText(
            """{"inboundSigningSecret":"my-signing-secret"}""",
        )

        val manager = ConfigManager(configPath = configPath)
        assertEquals("my-signing-secret", manager.signingSecret())
        tmpDir.deleteRecursively()
    }

    @Test
    fun `signingSecret falls back to legacy webhookToken when inboundSigningSecret is blank`() {
        val tmpDir = Files.createTempDirectory("iris-secret-signing-fallback").toFile()
        val configPath = tmpDir.resolve("config.json").absolutePath
        tmpDir.resolve("config.json").writeText(
            """{"webhookToken":"legacy-fallback"}""",
        )

        val manager = ConfigManager(configPath = configPath)
        assertEquals("legacy-fallback", manager.signingSecret())
        tmpDir.deleteRecursively()
    }
}
