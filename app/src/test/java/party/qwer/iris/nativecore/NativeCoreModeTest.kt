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
}
