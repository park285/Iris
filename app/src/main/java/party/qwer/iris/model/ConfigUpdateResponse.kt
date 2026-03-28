package party.qwer.iris.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ConfigUpdateResponse(
    val success: Boolean = true,
    val name: String,
    val persisted: Boolean,
    val applied: Boolean,
    val requiresRestart: Boolean,
    val user: ConfigState,
    val runtimeApplied: ConfigState,
    val discovered: ConfigDiscoveredState,
    @SerialName("pending_restart")
    val pendingRestart: ConfigPendingRestart,
)
