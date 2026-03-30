package party.qwer.iris.config

import kotlin.test.Test
import kotlin.test.assertEquals

class ConfigPathPolicyTest {
    @Test
    fun `resolveConfigPath returns env override when set`() {
        assertEquals("/custom/path.json", ConfigPathPolicy.resolveConfigPath("/custom/path.json"))
    }

    @Test
    fun `resolveConfigPath returns default when env is null`() {
        assertEquals("/data/iris/config.json", ConfigPathPolicy.resolveConfigPath(null))
    }

    @Test
    fun `resolveConfigPath returns default when env is blank`() {
        assertEquals("/data/iris/config.json", ConfigPathPolicy.resolveConfigPath("  "))
    }

    @Test
    fun `resolveConfigPath returns default when env is empty`() {
        assertEquals("/data/iris/config.json", ConfigPathPolicy.resolveConfigPath(""))
    }

    @Test
    fun `resolveLogDirectory returns env override when set`() {
        assertEquals("/custom/logs", ConfigPathPolicy.resolveLogDirectory("/custom/logs"))
    }

    @Test
    fun `resolveLogDirectory returns default when env is null`() {
        assertEquals("/data/iris/logs", ConfigPathPolicy.resolveLogDirectory(null))
    }

    @Test
    fun `resolveLogDirectory returns default when env is blank`() {
        assertEquals("/data/iris/logs", ConfigPathPolicy.resolveLogDirectory("  "))
    }
}
