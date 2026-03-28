package party.qwer.iris.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class QueryColumn(
    val name: String,
    val sqliteType: String,
)

@Serializable
data class QueryResponse(
    val rowCount: Int,
    val columns: List<QueryColumn>,
    val rows: List<List<JsonElement?>>,
)
