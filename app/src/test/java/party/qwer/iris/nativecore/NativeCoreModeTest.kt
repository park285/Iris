package party.qwer.iris.nativecore

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeCoreModeTest {
    @Test
    fun `defaults to off when env is missing`() {
        val config = NativeCoreModeConfig.fromEnv(emptyMap())

        assertEquals(NativeCoreMode.OFF, config.mode)
        assertEquals("/data/iris/lib/libiris_native_core.so", config.libraryPath)
        assertFalse(config.requiresLoad)
    }

    @Test
    fun `parses off shadow and on modes case insensitively`() {
        assertEquals(NativeCoreMode.OFF, NativeCoreModeConfig.fromEnv(mapOf("IRIS_NATIVE_CORE" to "OFF")).mode)
        assertEquals(NativeCoreMode.SHADOW, NativeCoreModeConfig.fromEnv(mapOf("IRIS_NATIVE_CORE" to "shadow")).mode)
        assertEquals(NativeCoreMode.ON, NativeCoreModeConfig.fromEnv(mapOf("IRIS_NATIVE_CORE" to "On")).mode)
    }

    @Test
    fun `malformed mode falls back to off with parse warning`() {
        val config = NativeCoreModeConfig.fromEnv(mapOf("IRIS_NATIVE_CORE" to "enabled"))

        assertEquals(NativeCoreMode.OFF, config.mode)
        assertEquals("unsupported IRIS_NATIVE_CORE value: enabled", config.parseWarning)
    }

    @Test
    fun `blank library path falls back to default path`() {
        val config = NativeCoreModeConfig.fromEnv(mapOf("IRIS_NATIVE_LIB_PATH" to "   "))

        assertEquals("/data/iris/lib/libiris_native_core.so", config.libraryPath)
    }

    @Test
    fun `custom library path is trimmed`() {
        val config = NativeCoreModeConfig.fromEnv(mapOf("IRIS_NATIVE_LIB_PATH" to " /tmp/libiris_native_core.so "))

        assertEquals("/tmp/libiris_native_core.so", config.libraryPath)
    }

    @Test
    fun `shadow and on require native load`() {
        assertFalse(NativeCoreModeConfig.fromEnv(mapOf("IRIS_NATIVE_CORE" to "off")).requiresLoad)
        assertTrue(NativeCoreModeConfig.fromEnv(mapOf("IRIS_NATIVE_CORE" to "shadow")).requiresLoad)
        assertTrue(NativeCoreModeConfig.fromEnv(mapOf("IRIS_NATIVE_CORE" to "on")).requiresLoad)
    }

    @Test
    fun `decrypt inherits global mode while new components default off`() {
        val config = NativeCoreModeConfig.fromEnv(mapOf("IRIS_NATIVE_CORE" to "on"))

        assertEquals(NativeCoreMode.ON, config.effectiveMode(NativeCoreComponent.DECRYPT))
        assertEquals(NativeCoreMode.OFF, config.effectiveMode(NativeCoreComponent.ROUTING))
        assertEquals(NativeCoreMode.OFF, config.effectiveMode(NativeCoreComponent.PARSERS))
        assertEquals(NativeCoreMode.OFF, config.effectiveMode(NativeCoreComponent.PROJECTIONS))
        assertEquals(NativeCoreMode.OFF, config.effectiveMode(NativeCoreComponent.WEBHOOK_PAYLOAD))
    }

    @Test
    fun `component mode env overrides global mode when native core is not off`() {
        val config =
            NativeCoreModeConfig.fromEnv(
                mapOf(
                    "IRIS_NATIVE_CORE" to "shadow",
                    "IRIS_NATIVE_DECRYPT" to "off",
                    "IRIS_NATIVE_ROUTING" to "on",
                    "IRIS_NATIVE_PARSERS" to "shadow",
                    "IRIS_NATIVE_PROJECTIONS" to "shadow",
                    "IRIS_NATIVE_WEBHOOK_PAYLOAD" to "OFF",
                ),
            )

        assertEquals(NativeCoreMode.OFF, config.effectiveMode(NativeCoreComponent.DECRYPT))
        assertEquals(NativeCoreMode.ON, config.effectiveMode(NativeCoreComponent.ROUTING))
        assertEquals(NativeCoreMode.SHADOW, config.effectiveMode(NativeCoreComponent.PARSERS))
        assertEquals(NativeCoreMode.SHADOW, config.effectiveMode(NativeCoreComponent.PROJECTIONS))
        assertEquals(NativeCoreMode.OFF, config.effectiveMode(NativeCoreComponent.WEBHOOK_PAYLOAD))
        assertTrue(config.requiresLoad)
    }

    @Test
    fun `native core off prevents component native modes`() {
        val config =
            NativeCoreModeConfig.fromEnv(
                mapOf(
                    "IRIS_NATIVE_CORE" to "off",
                    "IRIS_NATIVE_DECRYPT" to "on",
                    "IRIS_NATIVE_ROUTING" to "on",
                ),
            )

        assertEquals(NativeCoreMode.OFF, config.effectiveMode(NativeCoreComponent.DECRYPT))
        assertEquals(NativeCoreMode.OFF, config.effectiveMode(NativeCoreComponent.ROUTING))
        assertFalse(config.requiresLoad)
    }

    @Test
    fun `new component inherit value is rejected and defaults off`() {
        val config =
            NativeCoreModeConfig.fromEnv(
                mapOf(
                    "IRIS_NATIVE_CORE" to "on",
                    "IRIS_NATIVE_ROUTING" to "inherit",
                ),
            )

        assertEquals(NativeCoreMode.OFF, config.effectiveMode(NativeCoreComponent.ROUTING))
        assertEquals("unsupported IRIS_NATIVE_ROUTING value: inherit", config.parseWarning)
    }

    @Test
    fun `strict flag defaults off and applies only to on components`() {
        val config =
            NativeCoreModeConfig.fromEnv(
                mapOf(
                    "IRIS_NATIVE_CORE" to "on",
                    "IRIS_NATIVE_DECRYPT" to "off",
                    "IRIS_NATIVE_ROUTING" to "on",
                    "IRIS_NATIVE_PARSERS" to "shadow",
                    "IRIS_NATIVE_WEBHOOK_PAYLOAD" to "off",
                    "IRIS_NATIVE_STRICT" to "on",
                ),
            )

        assertFalse(config.strictMode(NativeCoreComponent.DECRYPT))
        assertTrue(config.strictMode(NativeCoreComponent.ROUTING))
        assertFalse(config.strictMode(NativeCoreComponent.PARSERS))
        assertFalse(config.strictMode(NativeCoreComponent.PROJECTIONS))
        assertFalse(config.strictMode(NativeCoreComponent.WEBHOOK_PAYLOAD))
    }

    @Test
    fun `component strict flags override global strict flag`() {
        val config =
            NativeCoreModeConfig.fromEnv(
                mapOf(
                    "IRIS_NATIVE_CORE" to "on",
                    "IRIS_NATIVE_DECRYPT" to "off",
                    "IRIS_NATIVE_ROUTING" to "on",
                    "IRIS_NATIVE_PARSERS" to "on",
                    "IRIS_NATIVE_PROJECTIONS" to "on",
                    "IRIS_NATIVE_STRICT" to "off",
                    "IRIS_NATIVE_STRICT_ROUTING" to "on",
                    "IRIS_NATIVE_STRICT_PARSERS" to "true",
                    "IRIS_NATIVE_STRICT_PROJECTIONS" to "yes",
                ),
            )

        assertTrue(config.strictMode(NativeCoreComponent.ROUTING))
        assertTrue(config.strictMode(NativeCoreComponent.PARSERS))
        assertTrue(config.strictMode(NativeCoreComponent.PROJECTIONS))
        assertFalse(config.strictMode(NativeCoreComponent.DECRYPT))
    }

    @Test
    fun `invalid strict flag falls back to safe default with warning`() {
        val config =
            NativeCoreModeConfig.fromEnv(
                mapOf(
                    "IRIS_NATIVE_CORE" to "on",
                    "IRIS_NATIVE_ROUTING" to "on",
                    "IRIS_NATIVE_STRICT" to "maybe",
                ),
            )

        assertFalse(config.strictMode(NativeCoreComponent.ROUTING))
        assertEquals("unsupported IRIS_NATIVE_STRICT value: maybe", config.parseWarning)
    }
}
