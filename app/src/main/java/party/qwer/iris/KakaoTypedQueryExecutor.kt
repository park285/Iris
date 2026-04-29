package party.qwer.iris

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import party.qwer.iris.model.QueryColumn
import party.qwer.iris.nativecore.NativeCoreHolder
import party.qwer.iris.nativecore.NativeQueryProjectedCell
import party.qwer.iris.nativecore.NativeQueryProjectionCellEnvelope
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
        val rowEnvelopes = ArrayList<List<NativeQueryProjectionCellEnvelope>>(minOf(maxRows, 64))
        queryConnection.rawQuery(sqlQuery, bindArgs).use { cursor ->
            val columnNames = cursor.columnNames
            val columnIndices =
                IntArray(columnNames.size) { index ->
                    cursor.getColumnIndexOrThrow(columnNames[index])
                }
            while (cursor.moveToNext() && rowEnvelopes.size < maxRows) {
                val row = ArrayList<NativeQueryProjectionCellEnvelope>(columnNames.size)
                for (index in columnNames.indices) {
                    row.add(cursor.readQueryCellEnvelope(columnIndices[index]))
                }
                rowEnvelopes.add(row)
            }
            val projectedRows =
                NativeCoreHolder.current().projectQueryRowsOrFallback(rowEnvelopes) {
                    projectTypedQueryRows(rowEnvelopes)
                }
            val observedTypes = MutableList(columnNames.size) { "UNKNOWN" }
            val resultRows = ArrayList<List<JsonElement?>>(projectedRows.size)
            for (projectedRow in projectedRows) {
                val row = ArrayList<JsonElement?>(columnNames.size)
                for (index in columnNames.indices) {
                    val projectedCell =
                        projectedRow.getOrNull(index) ?: NativeQueryProjectedCell(sqliteType = "UNKNOWN")
                    row.add(projectedCell.value)
                    observedTypes[index] = mergeObservedType(observedTypes[index], projectedCell.sqliteType)
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

private fun android.database.Cursor.readQueryCellEnvelope(index: Int): NativeQueryProjectionCellEnvelope =
    when (getType(index)) {
        android.database.Cursor.FIELD_TYPE_NULL ->
            typedQueryCellEnvelope(sqliteType = "NULL")
        android.database.Cursor.FIELD_TYPE_INTEGER ->
            typedQueryCellEnvelope(sqliteType = "INTEGER", longValue = getLong(index))
        android.database.Cursor.FIELD_TYPE_FLOAT ->
            typedQueryCellEnvelope(sqliteType = "FLOAT", doubleValue = getDouble(index))
        android.database.Cursor.FIELD_TYPE_STRING ->
            typedQueryCellEnvelope(sqliteType = "TEXT", textValue = getString(index).orEmpty())
        android.database.Cursor.FIELD_TYPE_BLOB ->
            typedQueryCellEnvelope(sqliteType = "BLOB", blobValue = getBlob(index))
        else ->
            typedQueryCellEnvelope(sqliteType = "UNKNOWN", textValue = getString(index).orEmpty())
    }

internal fun typedQueryCellEnvelope(
    sqliteType: String,
    longValue: Long? = null,
    doubleValue: Double? = null,
    textValue: String? = null,
    blobValue: ByteArray? = null,
): NativeQueryProjectionCellEnvelope =
    NativeQueryProjectionCellEnvelope(
        sqliteType = sqliteType,
        longValue = longValue,
        doubleValue = doubleValue,
        textValue = textValue,
        blob = blobValue?.map { byte -> byte.toInt() and 0xff },
    )

internal fun projectTypedQueryRows(rows: List<List<NativeQueryProjectionCellEnvelope>>): List<List<NativeQueryProjectedCell>> = rows.map { row -> row.map(::projectTypedQueryCell) }

internal fun projectTypedQueryCell(envelope: NativeQueryProjectionCellEnvelope): NativeQueryProjectedCell =
    when (envelope.sqliteType) {
        "NULL" -> NativeQueryProjectedCell(sqliteType = "NULL")
        "INTEGER" -> NativeQueryProjectedCell(sqliteType = "INTEGER", value = JsonPrimitive(envelope.longValue ?: 0L))
        "FLOAT" -> NativeQueryProjectedCell(sqliteType = "FLOAT", value = JsonPrimitive(envelope.doubleValue ?: 0.0))
        "TEXT" -> NativeQueryProjectedCell(sqliteType = "TEXT", value = JsonPrimitive(envelope.textValue.orEmpty()))
        "BLOB" ->
            NativeQueryProjectedCell(
                sqliteType = "BLOB",
                value =
                    JsonPrimitive(
                        Base64.getEncoder().encodeToString(
                            envelope.blob
                                .orEmpty()
                                .map { value -> value.toByte() }
                                .toByteArray(),
                        ),
                    ),
            )
        else -> NativeQueryProjectedCell(sqliteType = "UNKNOWN", value = JsonPrimitive(envelope.textValue.orEmpty()))
    }

internal fun mergeObservedType(
    current: String,
    observed: String,
): String =
    when {
        current == "UNKNOWN" -> observed
        current == "NULL" && observed != "NULL" -> observed
        else -> current
    }
