package party.qwer.iris.storage

data class QuerySpec<T>(
    val sql: String,
    val bindArgs: List<SqlArg>,
    val maxRows: Int,
    val mapper: (SqlRow) -> T,
)

interface SqlClient {
    fun <T> query(spec: QuerySpec<T>): List<T>

    fun <T> querySingle(spec: QuerySpec<T>): T? = query(spec.copy(maxRows = 1)).firstOrNull()
}
