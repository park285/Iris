package party.qwer.iris

import kotlin.test.Test
import kotlin.test.assertEquals

class BridgeRuntimeConfigTest {
    @Test
    fun `bridge resolution falls back to env token when config is unreadable`() {
        val resolution =
            BridgeBootstrapConfigResolver.resolve(
                env =
                    mapOf(
                        "IRIS_BRIDGE_TOKEN" to "bridge-secret",
                        "IRIS_CONFIG_PATH" to "/tmp/missing.json",
                    ),
                fileReader = { null },
            )

        assertEquals("bridge-secret", resolution.token)
        assertEquals(BridgeTokenSource.ENV_FALLBACK, resolution.source)
        assertEquals("/tmp/missing.json", resolution.configPath)
    }

    @Test
    fun `prefers config bridge token over env fallback`() {
        val resolution =
            BridgeBootstrapConfigResolver.resolve(
                env =
                    mapOf(
                        "IRIS_CONFIG_PATH" to "/tmp/config.json",
                        "IRIS_BRIDGE_TOKEN" to "env-bridge-token",
                    ),
                fileReader = {
                    """
                    {
                      "bridgeToken": "config-bridge-token"
                    }
                    """.trimIndent()
                },
            )

        assertEquals("config-bridge-token", resolution.token)
        assertEquals(BridgeTokenSource.CONFIG_FILE, resolution.source)
        assertEquals("/tmp/config.json", resolution.configPath)
    }

    @Test
    fun `falls back to env bridge token when config field is absent`() {
        val resolution =
            BridgeBootstrapConfigResolver.resolve(
                env =
                    mapOf(
                        "IRIS_CONFIG_PATH" to "/tmp/config.json",
                        "IRIS_BRIDGE_TOKEN" to "env-bridge-token",
                    ),
                fileReader = {
                    """
                    {
                      "botControlToken": "legacy-control-token"
                    }
                    """.trimIndent()
                },
            )

        assertEquals("env-bridge-token", resolution.token)
        assertEquals(BridgeTokenSource.ENV_FALLBACK, resolution.source)
        assertEquals("/tmp/config.json", resolution.configPath)
    }

    @Test
    fun `returns blank token when config is unreadable and env is absent`() {
        val resolution =
            BridgeBootstrapConfigResolver.resolve(
                env = emptyMap(),
                fileReader = { null },
            )

        assertEquals("", resolution.token)
        assertEquals(BridgeTokenSource.NONE, resolution.source)
        assertEquals("/data/iris/config.json", resolution.configPath)
    }

    @Test
    fun `shared runtime path policy drives bridge config default path`() {
        assertEquals(
            IrisRuntimePathPolicy.resolve(emptyMap()).configPath,
            BridgeBootstrapConfigResolver.resolve(env = emptyMap(), fileReader = { null }).configPath,
        )
    }
}
