package party.qwer.iris.persistence

import java.io.Closeable

interface SqliteRow {
    fun getLong(columnIndex: Int): Long

    fun getString(columnIndex: Int): String

    fun getStringOrNull(columnIndex: Int): String?

    fun getInt(columnIndex: Int): Int

    fun isNull(columnIndex: Int): Boolean
}

interface SqliteDriver : Closeable {
    fun execute(sql: String)

    fun queryLong(
        sql: String,
        vararg args: Any?,
    ): Long?

    fun <T> query(
        sql: String,
        args: List<Any?> = emptyList(),
        mapRow: (SqliteRow) -> T,
    ): List<T>

    fun update(
        sql: String,
        args: List<Any?> = emptyList(),
    ): Int

    fun <T> inImmediateTransaction(block: SqliteDriver.() -> T): T
}
