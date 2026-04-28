package party.qwer.iris.nativecore

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
internal data class NativeWebhookPayloadBatchRequest(
    val items: List<NativeWebhookPayloadBatchItem>,
)

@Serializable
internal data class NativeWebhookPayloadBatchItem(
    val command: NativeWebhookCommand,
    val route: String,
    val messageId: String,
)

@Serializable
internal data class NativeWebhookCommand(
    val text: String,
    val room: String,
    val sender: String,
    val userId: String,
    val sourceLogId: Long,
    val chatLogId: String? = null,
    val roomType: String? = null,
    val roomLinkId: String? = null,
    val threadId: String? = null,
    val threadScope: Int? = null,
    val messageType: String? = null,
    val attachment: String? = null,
    val eventPayload: JsonElement? = null,
)

@Serializable
internal data class NativeWebhookPayloadBatchResponse(
    val items: List<NativeWebhookPayloadBatchResult>,
)

@Serializable
internal data class NativeWebhookPayloadBatchResult(
    val ok: Boolean,
    val payloadJson: String? = null,
    val error: String? = null,
)
