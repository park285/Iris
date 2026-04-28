package party.qwer.iris.nativecore

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
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
    fun `on mode self test error result records failed self test without version`() {
        val runtime =
            NativeCoreRuntime.create(
                env = mapOf("IRIS_NATIVE_CORE" to "on"),
                loader = {},
                jni = FakeJni(selfTestResult = "error:panic in native core"),
            )

        val diagnostics = runtime.diagnostics()
        assertTrue(diagnostics.loaded)
        assertFalse(diagnostics.selfTestOk)
        assertNull(diagnostics.version)
        assertEquals("native core self-test failed", diagnostics.lastError)
        assertEquals("native core self-test failed", diagnostics.readinessFailureReason())
    }

    @Test
    fun `on mode self test error result exposes no enabled components and skips native decrypt`() {
        val jni = FakeJni(selfTestResult = "error:panic in native core", decryptResult = "rust-result")
        val runtime =
            NativeCoreRuntime.create(
                env = mapOf("IRIS_NATIVE_CORE" to "on"),
                loader = {},
                jni = jni,
            )

        val result = runtime.decryptOrFallback(encType = 0, ciphertext = "cipher", userId = 1L) { "kotlin-result" }
        val diagnostics = runtime.diagnostics()

        assertEquals(emptyList(), diagnostics.enabledComponents)
        assertEquals("kotlin-result", result)
        assertNull(jni.lastDecryptRequest)
        assertEquals(0L, diagnostics.callFailures)
    }

    @Test
    fun `shadow mode self test error result skips native shadow decrypt`() {
        val jni = FakeJni(selfTestResult = "error:panic in native core", decryptResult = "rust-result")
        val runtime =
            NativeCoreRuntime.create(
                env = mapOf("IRIS_NATIVE_CORE" to "shadow"),
                loader = {},
                jni = jni,
            )

        val result = runtime.decryptOrFallback(encType = 0, ciphertext = "cipher", userId = 1L) { "kotlin-result" }
        val diagnostics = runtime.diagnostics()

        assertEquals("kotlin-result", result)
        assertNull(jni.lastDecryptRequest)
        assertEquals(0L, diagnostics.callFailures)
        assertEquals(0L, diagnostics.shadowMismatches["decrypt"])
    }

    @Test
    fun `decrypt sends request json with encType ciphertext and userId`() {
        val jni = FakeJni()
        val runtime =
            NativeCoreRuntime.create(
                env = mapOf("IRIS_NATIVE_CORE" to "on"),
                loader = {},
                jni = jni,
            )

        runtime.decryptOrFallback(encType = 7, ciphertext = "cipher-secret", userId = 123L) { "kotlin-result" }

        val request = Json.decodeFromString<JsonObject>(jni.lastDecryptRequest!!.decodeToString())
        assertEquals(setOf("items"), request.keys)
        val items = request.getValue("items").jsonArray
        assertEquals(1, items.size)
        val item = items.single().jsonObject
        assertEquals(setOf("encType", "ciphertext", "userId"), item.keys)
        assertEquals(7, item.getValue("encType").jsonPrimitive.int)
        assertEquals("cipher-secret", item.getValue("ciphertext").jsonPrimitive.content)
        assertEquals(123L, item.getValue("userId").jsonPrimitive.long)
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
        assertEquals("native decrypt failed", runtime.diagnostics().lastError)
    }

    @Test
    fun `decrypt in on mode falls back when native response is malformed`() {
        val runtime = runtimeWithNativeResponse("not-json")

        val result = runtime.decryptOrFallback(encType = 0, ciphertext = "cipher", userId = 1L) { "kotlin-result" }

        assertEquals("kotlin-result", result)
        assertEquals(1L, runtime.diagnostics().callFailures)
        assertEquals("native decrypt failed", runtime.diagnostics().lastError)
    }

    @Test
    fun `decrypt in on mode falls back when native response is empty`() {
        val runtime = runtimeWithNativeResponse("""{"items":[]}""")

        val result = runtime.decryptOrFallback(encType = 0, ciphertext = "cipher", userId = 1L) { "kotlin-result" }

        assertEquals("kotlin-result", result)
        assertEquals(1L, runtime.diagnostics().callFailures)
        assertEquals("native decrypt failed", runtime.diagnostics().lastError)
    }

    @Test
    fun `decrypt in on mode falls back when native response reports error without leaking raw error`() {
        val runtime =
            runtimeWithNativeResponse(
                """{"items":[{"ok":false,"error":"ciphertext=secret userId=123 plaintext=leak"}]}""",
            )

        val result = runtime.decryptOrFallback(encType = 0, ciphertext = "cipher", userId = 1L) { "kotlin-result" }
        val diagnostics = runtime.diagnostics()

        assertEquals("kotlin-result", result)
        assertEquals(1L, diagnostics.callFailures)
        assertEquals("native decrypt failed", diagnostics.lastError)
        assertFalse(diagnostics.lastError!!.contains("secret"))
        assertFalse(diagnostics.lastError.contains("123"))
        assertFalse(diagnostics.lastError.contains("leak"))
    }

    @Test
    fun `decrypt in on mode falls back when native response is missing plaintext`() {
        val runtime = runtimeWithNativeResponse("""{"items":[{"ok":true}]}""")

        val result = runtime.decryptOrFallback(encType = 0, ciphertext = "cipher", userId = 1L) { "kotlin-result" }

        assertEquals("kotlin-result", result)
        assertEquals(1L, runtime.diagnostics().callFailures)
        assertEquals("native decrypt failed", runtime.diagnostics().lastError)
    }

    private fun runtimeWithNativeResponse(response: String): NativeCoreRuntime =
        NativeCoreRuntime.create(
            env = mapOf("IRIS_NATIVE_CORE" to "on"),
            loader = {},
            jni = FakeJni(rawDecryptResponse = response),
        )

    private class FakeJni(
        private val selfTestResult: String = "iris-native-core:test",
        private val decryptResult: String = "kotlin-result",
        private val decryptError: RuntimeException? = null,
        private val rawDecryptResponse: String? = null,
    ) : NativeCoreJniBridge {
        var lastDecryptRequest: ByteArray? = null
            private set

        override fun nativeSelfTest(): String = selfTestResult

        override fun decryptBatch(requestJsonBytes: ByteArray): ByteArray {
            lastDecryptRequest = requestJsonBytes
            decryptError?.let { throw it }
            return (rawDecryptResponse ?: """{"items":[{"ok":true,"plaintext":"$decryptResult"}]}""").encodeToByteArray()
        }
    }
}
