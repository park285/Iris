package party.qwer.iris.nativecore

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import party.qwer.iris.IrisLogger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal class NativeCoreRuntime private constructor(
    private val config: NativeCoreModeConfig,
    private val jni: NativeCoreJniBridge,
    private val loaded: Boolean,
    private val selfTestResult: String?,
    private val loadError: String?,
) {
    private val nativeDecryptFailure = "native decrypt failed"
    private val json = Json { ignoreUnknownKeys = true }
    private val callFailures = AtomicLong(0)
    private val decryptMismatches = AtomicLong(0)
    private val lastErrorRef = AtomicReference(loadError)

    fun diagnostics(): NativeCoreDiagnostics =
        NativeCoreDiagnostics(
            mode = config.mode.name.lowercase(),
            loaded = loaded,
            libraryPath = config.libraryPath,
            version = selfTestResult,
            enabledComponents = if (config.mode == NativeCoreMode.ON && loaded) listOf("decrypt") else emptyList(),
            selfTestOk = loaded && selfTestResult != null,
            callFailures = callFailures.get(),
            shadowMismatches = mapOf("decrypt" to decryptMismatches.get()),
            lastError = lastErrorRef.get(),
        )

    fun decryptOrFallback(
        encType: Int,
        ciphertext: String,
        userId: Long,
        kotlinDecrypt: () -> String,
    ): String {
        if (!loaded || config.mode == NativeCoreMode.OFF) {
            return kotlinDecrypt()
        }
        return when (config.mode) {
            NativeCoreMode.OFF -> kotlinDecrypt()
            NativeCoreMode.SHADOW -> decryptShadow(encType, ciphertext, userId, kotlinDecrypt)
            NativeCoreMode.ON -> decryptOn(encType, ciphertext, userId, kotlinDecrypt)
        }
    }

    private fun decryptShadow(
        encType: Int,
        ciphertext: String,
        userId: Long,
        kotlinDecrypt: () -> String,
    ): String {
        val kotlinResult = kotlinDecrypt()
        val nativeResult =
            runCatching { decryptNative(encType, ciphertext, userId) }
                .onFailure { recordNativeFailure() }
                .getOrNull()
        if (nativeResult != null && nativeResult != kotlinResult) {
            decryptMismatches.incrementAndGet()
            IrisLogger.warn("[NativeCore] shadow mismatch component=decrypt")
        }
        return kotlinResult
    }

    private fun decryptOn(
        encType: Int,
        ciphertext: String,
        userId: Long,
        kotlinDecrypt: () -> String,
    ): String =
        runCatching { decryptNative(encType, ciphertext, userId) }
            .getOrElse {
                recordNativeFailure()
                kotlinDecrypt()
            }

    private fun recordNativeFailure() {
        callFailures.incrementAndGet()
        lastErrorRef.set(nativeDecryptFailure)
        IrisLogger.error("[NativeCore] native call failed: $nativeDecryptFailure")
    }

    private fun decryptNative(
        encType: Int,
        ciphertext: String,
        userId: Long,
    ): String {
        val request = DecryptBatchRequest(listOf(DecryptBatchItem(encType, ciphertext, userId)))
        val rawResponse = jni.decryptBatch(json.encodeToString(request).encodeToByteArray()).decodeToString()
        val response = json.decodeFromString<DecryptBatchResponse>(rawResponse)
        val first = response.items.firstOrNull() ?: error(nativeDecryptFailure)
        if (!first.ok) {
            error(nativeDecryptFailure)
        }
        return first.plaintext ?: error(nativeDecryptFailure)
    }

    companion object {
        fun create(
            env: Map<String, String> = System.getenv(),
            loader: (String) -> Unit = System::load,
            jni: NativeCoreJniBridge = NativeCoreJni,
        ): NativeCoreRuntime {
            val config = NativeCoreModeConfig.fromEnv(env)
            config.parseWarning?.let { IrisLogger.warn("[NativeCore] $it") }
            if (!config.requiresLoad) {
                return NativeCoreRuntime(config, jni, loaded = false, selfTestResult = null, loadError = null)
            }
            return runCatching {
                loader(config.libraryPath)
                val selfTest = jni.nativeSelfTest()
                NativeCoreRuntime(config, jni, loaded = true, selfTestResult = selfTest, loadError = null)
            }.getOrElse { error ->
                IrisLogger.error("[NativeCore] failed to load native core: ${error.message}", error)
                NativeCoreRuntime(
                    config = config,
                    jni = jni,
                    loaded = false,
                    selfTestResult = null,
                    loadError = error.message ?: error::class.java.simpleName,
                )
            }
        }
    }
}

@Serializable
private data class DecryptBatchRequest(
    val items: List<DecryptBatchItem>,
)

@Serializable
private data class DecryptBatchItem(
    val encType: Int,
    val ciphertext: String,
    val userId: Long,
)

@Serializable
private data class DecryptBatchResponse(
    val items: List<DecryptBatchResult>,
)

@Serializable
private data class DecryptBatchResult(
    val ok: Boolean,
    val plaintext: String? = null,
    val error: String? = null,
)
