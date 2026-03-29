package party.qwer.iris.storage

import party.qwer.iris.QueryExecutionResult

class KakaoDbSqlClient(
    private val executeQueryTyped: (String, Array<String?>?, Int) -> QueryExecutionResult,
) : SqlClient {
    override fun <T> query(spec: QuerySpec<T>): List<T> {
        val bindArray = if (spec.bindArgs.isEmpty()) null else spec.bindArgs.toBindArray()
        val result = executeQueryTyped(spec.sql, bindArray, spec.maxRows)
        val index = result.columns.withIndex().associate { (i, col) -> col.name to i }
        return result.rows.map { values -> spec.mapper(SqlRow(index, values)) }
    }
}
