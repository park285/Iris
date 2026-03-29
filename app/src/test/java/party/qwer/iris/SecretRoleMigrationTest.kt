package party.qwer.iris

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class SecretRoleMigrationTest {
    @Test
    fun `role fields are loaded from config`() {
        val tmpDir = Files.createTempDirectory("iris-secret-role").toFile()
        val configPath = tmpDir.resolve("config.json").absolutePath
        tmpDir.resolve("config.json").writeText(
            """{
                "inboundSigningSecret":"my-signing",
                "outboundWebhookToken":"my-outbound",
                "botControlToken":"my-control"
            }""",
        )

        val manager = ConfigManager(configPath = configPath)

        assertEquals("my-signing", manager.inboundSigningSecret)
        assertEquals("my-outbound", manager.outboundWebhookToken)
        assertEquals("my-control", manager.botControlToken)
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
    fun `save and reload preserves role fields`() {
        val tmpDir = Files.createTempDirectory("iris-secret-save").toFile()
        val configPath = tmpDir.resolve("config.json").absolutePath
        tmpDir.resolve("config.json").writeText(
            """{
                "inboundSigningSecret":"signing-val",
                "outboundWebhookToken":"outbound-val",
                "botControlToken":"control-val"
            }""",
        )

        val manager = ConfigManager(configPath = configPath)
        manager.saveConfigNow()

        val reloaded = ConfigManager(configPath = configPath)
        assertEquals("signing-val", reloaded.inboundSigningSecret)
        assertEquals("outbound-val", reloaded.outboundWebhookToken)
        assertEquals("control-val", reloaded.botControlToken)
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
}
