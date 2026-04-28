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
    val lastError: String? = null,
) {
    fun readinessFailureReason(): String? =
        when (mode) {
            "on" ->
                when {
                    !loaded -> "native core not loaded"
                    !selfTestOk -> "native core self-test failed"
                    else -> null
                }

            else -> null
        }
}
