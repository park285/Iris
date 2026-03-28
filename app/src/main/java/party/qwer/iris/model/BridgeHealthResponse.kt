package party.qwer.iris.model

import kotlinx.serialization.Serializable

@Serializable
data class ImageBridgeHealthCheck(
    val name: String,
    val ok: Boolean,
    val detail: String? = null,
)

@Serializable
data class ImageBridgeDiscoveryHook(
    val name: String,
    val installed: Boolean,
    val installError: String? = null,
    val invocationCount: Int,
    val lastSeenEpochMs: Long? = null,
    val lastSummary: String? = null,
)

@Serializable
data class ImageBridgeHealthResult(
    val reachable: Boolean,
    val running: Boolean,
    val specReady: Boolean,
    val checkedAtEpochMs: Long? = null,
    val restartCount: Int,
    val lastCrashMessage: String? = null,
    val checks: List<ImageBridgeHealthCheck> = emptyList(),
    val discoveryInstallAttempted: Boolean = false,
    val discoveryHooks: List<ImageBridgeDiscoveryHook> = emptyList(),
    val error: String? = null,
)
