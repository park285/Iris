package party.qwer.iris.nativecore

import kotlinx.serialization.Serializable
import party.qwer.iris.ParsedCommand

@Serializable
internal data class NativeIngressBatchRequest(
    val items: List<NativeIngressBatchItem>,
    val commandRoutePrefixes: List<NativeRouteEntry> = emptyList(),
    val imageMessageTypeRoutes: List<NativeRouteEntry> = emptyList(),
)

@Serializable
internal data class NativeIngressBatchItem(
    val command: NativeWebhookCommand,
    val messageId: String? = null,
)

@Serializable
internal data class NativeIngressBatchResponse(
    val items: List<NativeIngressBatchResult>,
)

@Serializable
internal data class NativeIngressBatchResult(
    val ok: Boolean,
    val kind: String = "NONE",
    val normalizedText: String = "",
    val webhookRoute: String? = null,
    val eventRoute: String? = null,
    val imageRoute: String? = null,
    val targetRoute: String? = null,
    val messageId: String? = null,
    val payloadJson: String? = null,
    val error: String? = null,
)

internal data class NativeIngressPlan(
    val parsedCommand: ParsedCommand,
    val targetRoute: String? = null,
    val messageId: String? = null,
    val payloadJson: String? = null,
)
