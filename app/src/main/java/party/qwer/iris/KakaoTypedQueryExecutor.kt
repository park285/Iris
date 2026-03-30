package party.qwer.iris

import kotlinx.serialization.json.JsonPrimitive
import party.qwer.iris.model.QueryColumn
import java.util.Base64

internal class KakaoTypedQueryExecutor(
    private val runtime: KakaoDbRuntime,
) {
    fun execute(
        sqlQuery: String,
        bindArgs: Array<String?>?,
        maxRows: Int,
    ): QueryExecutionResult {
        val queryConnection = runtime.openDetachedReadConnection()
        try {
            return readQueryRows(queryConnection, sqlQuery, bindArgs, maxRows.coerceAtLeast(1))
        } finally {
            queryConnection.close()
        }
    }

    private fun readQueryRows(
        queryConnection: android.database.sqlite.SQLiteDatabase,
        sqlQuery: String,
        bindArgs: Array<String?>?,
        maxRows: Int,
    ): QueryExecutionResult {
        val resultRows = ArrayList<List<kotlinx.serialization.json.JsonElement?>>(minOf(maxRows, 64))
        queryConnection.rawQuery(sqlQuery, bindArgs).use { cursor ->
            val columnNames = cursor.columnNames
            val columnIndices =
                IntArray(columnNames.size) { index ->
                    cursor.getColumnIndexOrThrow(columnNames[index])
                }
            val observedTypes = MutableList(columnNames.size) { "UNKNOWN" }
            while (cursor.moveToNext() && resultRows.size < maxRows) {
                val row = ArrayList<kotlinx.serialization.json.JsonElement?>(columnNames.size)
                for (index in columnNames.indices) {
                    val (cell, sqliteType) = cursor.readQueryCell(columnIndices[index])
                    row.add(cell)
                    observedTypes[index] = mergeObservedType(observedTypes[index], sqliteType)
                }
                resultRows.add(row)
            }
            val columns =
                columnNames.indices.map { index ->
                    QueryColumn(
                        name = columnNames[index],
                        sqliteType = observedTypes[index],
                    )
                }
            return QueryExecutionResult(
                columns = columns,
                rows = resultRows,
            )
        }
    }
}

private fun android.database.Cursor.readQueryCell(index: Int): Pair<kotlinx.serialization.json.JsonElement?, String> =
    when (getType(index)) {
        android.database.Cursor.FIELD_TYPE_NULL -> null to "NULL"
        android.database.Cursor.FIELD_TYPE_INTEGER -> JsonPrimitive(getLong(index)) to "INTEGER"
        android.database.Cursor.FIELD_TYPE_FLOAT -> JsonPrimitive(getDouble(index)) to "FLOAT"
        android.database.Cursor.FIELD_TYPE_STRING -> JsonPrimitive(getString(index).orEmpty()) to "TEXT"
        android.database.Cursor.FIELD_TYPE_BLOB -> JsonPrimitive(Base64.getEncoder().encodeToString(getBlob(index))) to "BLOB"
        else -> JsonPrimitive(getString(index).orEmpty()) to "UNKNOWN"
    }

private fun mergeObservedType(
    current: String,
    observed: String,
): String =
    when {
        current == "UNKNOWN" -> observed
        current == "NULL" && observed != "NULL" -> observed
        else -> current
    }
