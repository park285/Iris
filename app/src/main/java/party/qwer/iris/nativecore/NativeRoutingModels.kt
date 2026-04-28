package party.qwer.iris.nativecore

import kotlinx.serialization.Serializable

@Serializable
internal data class NativeRouteEntry(
    val route: String,
    val values: List<String>,
)

@Serializable
internal data class NativeRoutingBatchRequest(
    val items: List<NativeRoutingBatchItem>,
    val commandRoutePrefixes: List<NativeRouteEntry> = emptyList(),
    val imageMessageTypeRoutes: List<NativeRouteEntry> = emptyList(),
)

@Serializable
internal data class NativeRoutingBatchItem(
    val text: String,
    val messageType: String? = null,
)

@Serializable
internal data class NativeRoutingBatchResponse(
    val items: List<NativeRoutingBatchResult>,
)

@Serializable
internal data class NativeRoutingBatchResult(
    val ok: Boolean,
    val kind: String = "NONE",
    val normalizedText: String = "",
    val webhookRoute: String? = null,
    val eventRoute: String? = null,
    val imageRoute: String? = null,
    val targetRoute: String? = null,
    val error: String? = null,
)

internal data class NativeRoutingDecision(
    val parsedCommand: party.qwer.iris.ParsedCommand,
    val webhookRoute: String? = null,
    val eventRoute: String? = null,
    val imageRoute: String? = null,
    val targetRoute: String? = null,
)
