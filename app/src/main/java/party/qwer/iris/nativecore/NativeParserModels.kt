package party.qwer.iris.nativecore

import kotlinx.serialization.Serializable

@Serializable
internal data class NativeParserBatchRequest(
    val items: List<NativeParserBatchItem>,
)

@Serializable
internal data class NativeParserBatchItem(
    val kind: String,
    val meta: String? = null,
    val raw: String? = null,
    val period: String? = null,
    val defaultDays: Long? = null,
)

@Serializable
internal data class NativeParserBatchResponse(
    val items: List<NativeParserBatchResult>,
)

@Serializable
internal data class NativeParserBatchResult(
    val kind: String,
    val ok: Boolean,
    val fallback: Boolean = false,
    val roomTitle: String? = null,
    val notices: List<NativeNoticeInfo> = emptyList(),
    val ids: List<Long> = emptyList(),
    val periodSpec: NativePeriodSpecInfo? = null,
    val error: String? = null,
)

@Serializable
internal data class NativeNoticeInfo(
    val content: String,
    val authorId: Long = 0L,
    val updatedAt: Long = 0L,
)

@Serializable
internal data class NativePeriodSpecInfo(
    val kind: String,
    val days: Long? = null,
    val seconds: Long? = null,
)
