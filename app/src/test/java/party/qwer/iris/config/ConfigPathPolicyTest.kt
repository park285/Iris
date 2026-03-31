package party.qwer.iris.config

import kotlin.test.Test
import kotlin.test.assertEquals

class ConfigPathPolicyTest {
    @Test
    fun `resolveConfigPath returns env override when set`() {
        assertEquals("/custom/path.json", ConfigPathPolicy.resolveConfigPath(mapOf("IRIS_CONFIG_PATH" to "/custom/path.json")))
    }

    @Test
    fun `resolveConfigPath returns default when env is null`() {
        assertEquals("/data/iris/config.json", ConfigPathPolicy.resolveConfigPath(emptyMap()))
    }

    @Test
    fun `resolveConfigPath returns default when env is blank`() {
        assertEquals("/data/iris/config.json", ConfigPathPolicy.resolveConfigPath(mapOf("IRIS_CONFIG_PATH" to "  ")))
    }

    @Test
    fun `resolveConfigPath returns default when env is empty`() {
        assertEquals("/data/iris/config.json", ConfigPathPolicy.resolveConfigPath(mapOf("IRIS_CONFIG_PATH" to "")))
    }

    @Test
    fun `resolveLogDirectory returns env override when set`() {
        assertEquals("/custom/logs", ConfigPathPolicy.resolveLogDirectory(mapOf("IRIS_LOG_DIR" to "/custom/logs")))
    }

    @Test
    fun `resolveLogDirectory returns default when env is null`() {
        assertEquals("/data/iris/logs", ConfigPathPolicy.resolveLogDirectory(emptyMap()))
    }

    @Test
    fun `resolveLogDirectory returns default when env is blank`() {
        assertEquals("/data/iris/logs", ConfigPathPolicy.resolveLogDirectory(mapOf("IRIS_LOG_DIR" to "  ")))
    }

    @Test
    fun `resolve paths honor IRIS_DATA_DIR base`() {
        val env = mapOf("IRIS_DATA_DIR" to "/custom/iris")

        assertEquals("/custom/iris/config.json", ConfigPathPolicy.resolveConfigPath(env))
        assertEquals("/custom/iris/logs", ConfigPathPolicy.resolveLogDirectory(env))
    }
}
