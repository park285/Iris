package party.qwer.iris.nativecore

import kotlinx.serialization.Serializable

@Serializable
internal data class NativeCoreDiagnostics(
    val mode: String,
    val loaded: Boolean,
    val libraryPath: String,
    val version: String? = null,
    val enabledComponents: List<String> = emptyList(),
    val selfTestOk: Boolean = false,
    val callFailures: Long = 0,
    val shadowMismatches: Map<String, Long> = emptyMap(),
    val componentStats: Map<String, NativeCoreComponentDiagnostics> = emptyMap(),
    val lastError: String? = null,
) {
    fun readinessFailureReason(): String? {
        val requiresNative =
            if (componentStats.isEmpty()) {
                mode.trim().lowercase() == "on"
            } else {
                componentStats.values.any { it.mode.trim().lowercase() == "on" }
            }
        if (!requiresNative) return null
        return when {
            !loaded -> "native core not loaded"
            !selfTestOk -> "native core self-test failed"
            else -> null
        }
    }
}

@Serializable
internal data class NativeCoreComponentDiagnostics(
    val mode: String,
    val jniCalls: Long = 0,
    val items: Long = 0,
    val fallbacks: Long = 0,
    val shadowMismatches: Long = 0,
    val totalNativeMicros: Long = 0,
    val maxNativeMicros: Long = 0,
    val averageNativeMicros: Long = 0,
    val averageItemNativeMicros: Long = 0,
    val fallbacksByKey: Map<String, Long> = emptyMap(),
    val fallbackReasons: Map<String, Long> = emptyMap(),
    val failureReasons: Map<String, Long> = emptyMap(),
    val shadowMismatchesByKey: Map<String, Long> = emptyMap(),
    val parserDefaultUses: Long = 0,
    val parserDefaultUsesByKey: Map<String, Long> = emptyMap(),
    val lastError: String? = null,
)
