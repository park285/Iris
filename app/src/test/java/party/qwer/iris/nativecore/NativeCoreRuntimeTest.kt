package party.qwer.iris.nativecore

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeCoreRuntimeTest {
    @Test
    fun `off mode skips loader and reports ready`() {
        var loadCalls = 0
        val runtime =
            NativeCoreRuntime.create(
                env = mapOf("IRIS_NATIVE_CORE" to "off"),
                loader = { loadCalls += 1 },
                jni = FakeJni(),
            )

        assertEquals(0, loadCalls)
        assertEquals("off", runtime.diagnostics().mode)
        assertFalse(runtime.diagnostics().loaded)
        assertNull(runtime.diagnostics().readinessFailureReason())
    }

    @Test
    fun `shadow mode load failure records error but stays ready`() {
        val runtime =
            NativeCoreRuntime.create(
                env = mapOf("IRIS_NATIVE_CORE" to "shadow"),
                loader = { error("missing library") },
                jni = FakeJni(),
            )

        val diagnostics = runtime.diagnostics()
        assertEquals("shadow", diagnostics.mode)
        assertFalse(diagnostics.loaded)
        assertEquals("missing library", diagnostics.lastError)
        assertNull(diagnostics.readinessFailureReason())
    }

    @Test
    fun `on mode load failure records readiness failure`() {
        val runtime =
            NativeCoreRuntime.create(
                env = mapOf("IRIS_NATIVE_CORE" to "on"),
                loader = { error("missing library") },
                jni = FakeJni(),
            )

        val diagnostics = runtime.diagnostics()
        assertEquals("on", diagnostics.mode)
        assertFalse(diagnostics.loaded)
        assertEquals("native core not loaded", diagnostics.readinessFailureReason())
    }

    @Test
    fun `successful load runs self test and records version`() {
        val runtime =
            NativeCoreRuntime.create(
                env = mapOf("IRIS_NATIVE_CORE" to "shadow"),
                loader = {},
                jni = FakeJni(selfTestResult = "iris-native-core:0.1.0"),
            )

        val diagnostics = runtime.diagnostics()
        assertTrue(diagnostics.loaded)
        assertTrue(diagnostics.selfTestOk)
        assertEquals("iris-native-core:0.1.0", diagnostics.version)
    }

    @Test
    fun `decrypt in shadow mode returns kotlin result and counts mismatch`() {
        val runtime =
            NativeCoreRuntime.create(
                env = mapOf("IRIS_NATIVE_CORE" to "shadow"),
                loader = {},
                jni = FakeJni(decryptResult = "rust-result"),
            )

        val result = runtime.decryptOrFallback(encType = 0, ciphertext = "cipher", userId = 1L) { "kotlin-result" }

        assertEquals("kotlin-result", result)
        assertEquals(1L, runtime.diagnostics().shadowMismatches["decrypt"])
    }

    @Test
    fun `decrypt in on mode returns native result when loaded`() {
        val runtime =
            NativeCoreRuntime.create(
                env = mapOf("IRIS_NATIVE_CORE" to "on"),
                loader = {},
                jni = FakeJni(decryptResult = "rust-result"),
            )

        val result = runtime.decryptOrFallback(encType = 0, ciphertext = "cipher", userId = 1L) { "kotlin-result" }

        assertEquals("rust-result", result)
    }

    @Test
    fun `decrypt in on mode falls back when native call fails`() {
        val runtime =
            NativeCoreRuntime.create(
                env = mapOf("IRIS_NATIVE_CORE" to "on"),
                loader = {},
                jni = FakeJni(decryptError = IllegalStateException("native failed")),
            )

        val result = runtime.decryptOrFallback(encType = 0, ciphertext = "cipher", userId = 1L) { "kotlin-result" }

        assertEquals("kotlin-result", result)
        assertEquals(1L, runtime.diagnostics().callFailures)
    }

    private class FakeJni(
        private val selfTestResult: String = "iris-native-core:test",
        private val decryptResult: String = "kotlin-result",
        private val decryptError: RuntimeException? = null,
    ) : NativeCoreJniBridge {
        override fun nativeSelfTest(): String = selfTestResult

        override fun decryptBatch(requestJsonBytes: ByteArray): ByteArray {
            decryptError?.let { throw it }
            return """{"items":[{"ok":true,"plaintext":"$decryptResult"}]}""".encodeToByteArray()
        }
    }
}
