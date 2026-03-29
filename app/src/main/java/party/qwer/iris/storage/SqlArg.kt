package party.qwer.iris.storage

sealed interface SqlArg {
    data class Str(
        val value: String,
    ) : SqlArg

    data class LongVal(
        val value: Long,
    ) : SqlArg

    data class IntVal(
        val value: Int,
    ) : SqlArg

    data object Null : SqlArg
}

fun List<SqlArg>.toBindArray(): Array<String?> =
    map { arg ->
        when (arg) {
            is SqlArg.Str -> arg.value
            is SqlArg.LongVal -> arg.value.toString()
            is SqlArg.IntVal -> arg.value.toString()
            SqlArg.Null -> null
        }
    }.toTypedArray()
