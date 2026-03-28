package party.qwer.iris

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import party.qwer.iris.model.QueryColumn
import party.qwer.iris.model.QueryResponse

internal fun requireQueryText(query: String) {
    if (query.isBlank()) {
        invalidRequest("query must not be blank")
    }
}

internal fun requireReadOnlyQuery(query: String) {
    if (!isReadOnlyQuery(query)) {
        invalidRequest("only SELECT, WITH...SELECT, and safe PRAGMA queries are allowed")
    }
}

internal fun buildQueryResponse(
    queryResult: QueryExecutionResult,
    decrypt: Boolean,
    config: ConfigProvider,
    decryptor: (Map<String, String?>, ConfigProvider) -> Map<String, String?> = ::decryptRow,
): QueryResponse {
    val legacyRows = toLegacyQueryRows(queryResult)
    val effectiveLegacyRows =
        if (decrypt) {
            legacyRows.map { row -> decryptor(row, config) }
        } else {
            legacyRows
        }
    val effectiveRows =
        queryResult.rows.indices.map { rowIndex ->
            applyLegacyRowOverlay(
                columns = queryResult.columns,
                originalRow = queryResult.rows[rowIndex],
                effectiveLegacyRow = effectiveLegacyRows[rowIndex],
            )
        }

    return QueryResponse(
        rowCount = effectiveRows.size,
        columns = queryResult.columns,
        rows = effectiveRows,
    )
}

internal fun toLegacyQueryRows(queryResult: QueryExecutionResult): List<Map<String, String?>> =
    queryResult.rows.map { row ->
        buildLegacyQueryRow(queryResult.columns, row)
    }

private fun buildLegacyQueryRow(
    columns: List<QueryColumn>,
    row: List<JsonElement?>,
): Map<String, String?> =
    columns.indices.associate { index ->
        columns[index].name to row[index]?.jsonPrimitive?.content
    }

private fun applyLegacyRowOverlay(
    columns: List<QueryColumn>,
    originalRow: List<JsonElement?>,
    effectiveLegacyRow: Map<String, String?>,
): List<JsonElement?> =
    columns.indices.map { index ->
        val originalCell = originalRow[index]
        val effectiveValue = effectiveLegacyRow[columns[index].name]
        when {
            effectiveValue == null -> null
            originalCell == null -> JsonPrimitive(effectiveValue)
            !originalCell.jsonPrimitive.isString && originalCell.jsonPrimitive.content == effectiveValue -> originalCell
            else -> JsonPrimitive(effectiveValue)
        }
    }
