package party.qwer.iris

import kotlinx.serialization.json.JsonElement
import party.qwer.iris.model.QueryColumn

data class QueryExecutionResult(
    val columns: List<QueryColumn>,
    val rows: List<List<JsonElement?>>,
)
