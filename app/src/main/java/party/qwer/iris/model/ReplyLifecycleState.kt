package party.qwer.iris.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ReplyLifecycleState {
    @SerialName("queued")
    QUEUED,

    @SerialName("preparing")
    PREPARING,

    @SerialName("prepared")
    PREPARED,

    @SerialName("sending")
    SENDING,

    @SerialName("handoff_completed")
    HANDOFF_COMPLETED,

    @SerialName("failed")
    FAILED,
}
