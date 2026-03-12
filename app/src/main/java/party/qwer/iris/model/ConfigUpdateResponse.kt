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
    val snapshot: ConfigState,
    val effective: ConfigState,
    @SerialName("pending_restart")
    val pendingRestart: ConfigPendingRestart,
)
