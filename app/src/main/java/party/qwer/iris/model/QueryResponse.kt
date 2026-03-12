package party.qwer.iris.model

import kotlinx.serialization.Serializable

@Serializable
data class QueryResponse(
    val rowCount: Int,
    val data: List<Map<String, String?>>,
)
