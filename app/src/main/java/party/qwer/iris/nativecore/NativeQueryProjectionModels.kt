package party.qwer.iris.nativecore

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
internal data class NativeQueryProjectionBatchRequest(
    val items: List<NativeQueryProjectionBatchItem>,
)

@Serializable
internal data class NativeQueryProjectionBatchItem(
    val cells: List<NativeQueryProjectionCellEnvelope>,
)

@Serializable
internal data class NativeQueryProjectionCellEnvelope(
    val sqliteType: String,
    val longValue: Long? = null,
    val doubleValue: Double? = null,
    val textValue: String? = null,
    val blob: List<Int>? = null,
)

@Serializable
internal data class NativeQueryProjectionBatchResponse(
    val items: List<NativeQueryProjectionBatchResult> = emptyList(),
)

@Serializable
internal data class NativeQueryProjectionBatchResult(
    val ok: Boolean = false,
    val cells: List<NativeQueryProjectedCell>? = null,
    val errorKind: String? = null,
    val error: String? = null,
)

@Serializable
internal data class NativeQueryProjectedCell(
    val sqliteType: String,
    val value: JsonElement? = null,
)
