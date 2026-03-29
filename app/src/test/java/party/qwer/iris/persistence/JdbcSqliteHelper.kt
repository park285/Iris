package party.qwer.iris.persistence

import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types

class JdbcSqliteHelper private constructor(
    private val connection: Connection,
) : SqliteDriver {
    companion object {
        fun inMemory(): JdbcSqliteHelper {
            val connection = DriverManager.getConnection("jdbc:sqlite::memory:")
            connection.autoCommit = true
            connection.createStatement().use { it.execute("PRAGMA journal_mode=WAL") }
            connection.createStatement().use { it.execute("PRAGMA busy_timeout=5000") }
            return JdbcSqliteHelper(connection)
        }
    }

    override fun execute(sql: String) {
        connection.createStatement().use { it.execute(sql) }
    }

    override fun queryLong(
        sql: String,
        vararg args: Any?,
    ): Long? {
        connection.prepareStatement(sql).use { statement ->
            bindArgs(statement, args.toList())
            statement.executeQuery().use { resultSet ->
                if (!resultSet.next()) {
                    return null
                }
                val value = resultSet.getLong(1)
                return if (resultSet.wasNull()) null else value
            }
        }
    }

    override fun <T> query(
        sql: String,
        args: List<Any?>,
        mapRow: (SqliteRow) -> T,
    ): List<T> {
        connection.prepareStatement(sql).use { statement ->
            bindArgs(statement, args)
            statement.executeQuery().use { resultSet ->
                val row = JdbcSqliteRow(resultSet)
                val results = mutableListOf<T>()
                while (resultSet.next()) {
                    results += mapRow(row)
                }
                return results
            }
        }
    }

    override fun update(
        sql: String,
        args: List<Any?>,
    ): Int {
        connection.prepareStatement(sql).use { statement ->
            bindArgs(statement, args)
            return statement.executeUpdate()
        }
    }

    override fun <T> inImmediateTransaction(block: SqliteDriver.() -> T): T {
        execute("BEGIN IMMEDIATE")
        return try {
            val result = block()
            execute("COMMIT")
            result
        } catch (exception: Exception) {
            execute("ROLLBACK")
            throw exception
        }
    }

    override fun close() {
        connection.close()
    }

    private fun bindArgs(
        statement: PreparedStatement,
        args: List<Any?>,
    ) {
        args.forEachIndexed { index, value ->
            val bindIndex = index + 1
            when (value) {
                null -> statement.setNull(bindIndex, Types.NULL)
                is Long -> statement.setLong(bindIndex, value)
                is Int -> statement.setInt(bindIndex, value)
                is String -> statement.setString(bindIndex, value)
                else -> statement.setString(bindIndex, value.toString())
            }
        }
    }

    private class JdbcSqliteRow(
        private val resultSet: ResultSet,
    ) : SqliteRow {
        override fun getLong(columnIndex: Int): Long = resultSet.getLong(columnIndex + 1)

        override fun getString(columnIndex: Int): String = resultSet.getString(columnIndex + 1)

        override fun getStringOrNull(columnIndex: Int): String? {
            val value = resultSet.getString(columnIndex + 1)
            return if (resultSet.wasNull()) null else value
        }

        override fun getInt(columnIndex: Int): Int = resultSet.getInt(columnIndex + 1)

        override fun isNull(columnIndex: Int): Boolean {
            resultSet.getObject(columnIndex + 1)
            return resultSet.wasNull()
        }
    }
}
