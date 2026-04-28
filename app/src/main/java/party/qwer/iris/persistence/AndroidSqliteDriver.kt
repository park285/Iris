package party.qwer.iris.persistence

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase

internal interface BindableSqliteStatement : AutoCloseable {
    fun bindNull(index: Int)

    fun bindLong(
        index: Int,
        value: Long,
    )

    fun bindString(
        index: Int,
        value: String,
    )

    fun executeUpdateDelete(): Int
}

internal fun executeBoundUpdate(
    statementFactory: (String) -> BindableSqliteStatement,
    sql: String,
    args: List<Any?>,
): Int =
    statementFactory(sql).use { stmt ->
        args.forEachIndexed { index, value ->
            val bindIndex = index + 1
            when (value) {
                null -> stmt.bindNull(bindIndex)
                is Long -> stmt.bindLong(bindIndex, value)
                is Int -> stmt.bindLong(bindIndex, value.toLong())
                is String -> stmt.bindString(bindIndex, value)
                else -> stmt.bindString(bindIndex, value.toString())
            }
        }
        stmt.executeUpdateDelete()
    }

internal class AndroidSqliteDriver(
    private val database: SQLiteDatabase,
) : SqliteDriver {
    override fun execute(sql: String) {
        if (shouldExecuteViaRawQuery(sql)) {
            database.rawQuery(sql, emptyArray()).use { cursor ->
                cursor.moveToFirst()
            }
            return
        }
        database.execSQL(sql)
    }

    override fun queryLong(
        sql: String,
        vararg args: Any?,
    ): Long? {
        val stringArgs = args.map { it?.toString() }.toTypedArray()
        database.rawQuery(sql, stringArgs).use { cursor ->
            if (!cursor.moveToFirst()) return null
            if (cursor.isNull(0)) return null
            return cursor.getLong(0)
        }
    }

    override fun <T> query(
        sql: String,
        args: List<Any?>,
        mapRow: (SqliteRow) -> T,
    ): List<T> {
        val stringArgs = args.map { it?.toString() }.toTypedArray()
        database.rawQuery(sql, stringArgs).use { cursor ->
            val results = mutableListOf<T>()
            val row = AndroidCursorRow(cursor)
            while (cursor.moveToNext()) {
                results.add(mapRow(row))
            }
            return results
        }
    }

    override fun update(
        sql: String,
        args: List<Any?>,
    ): Int = executeBoundUpdate(::compileStatement, sql, args)

    override fun <T> inImmediateTransaction(block: SqliteDriver.() -> T): T {
        database.beginTransactionNonExclusive()
        return try {
            val result = block()
            database.setTransactionSuccessful()
            result
        } finally {
            database.endTransaction()
        }
    }

    override fun close() {
        database.close()
    }

    private class AndroidCursorRow(
        private val cursor: Cursor,
    ) : SqliteRow {
        override fun getLong(columnIndex: Int): Long = cursor.getLong(columnIndex)

        override fun getString(columnIndex: Int): String = cursor.getString(columnIndex)

        override fun getStringOrNull(columnIndex: Int): String? = if (cursor.isNull(columnIndex)) null else cursor.getString(columnIndex)

        override fun getInt(columnIndex: Int): Int = cursor.getInt(columnIndex)

        override fun isNull(columnIndex: Int): Boolean = cursor.isNull(columnIndex)
    }

    private fun compileStatement(sql: String): BindableSqliteStatement = AndroidBindableSqliteStatement(database.compileStatement(sql))

    private class AndroidBindableSqliteStatement(
        private val delegate: android.database.sqlite.SQLiteStatement,
    ) : BindableSqliteStatement {
        override fun bindNull(index: Int) {
            delegate.bindNull(index)
        }

        override fun bindLong(
            index: Int,
            value: Long,
        ) {
            delegate.bindLong(index, value)
        }

        override fun bindString(
            index: Int,
            value: String,
        ) {
            delegate.bindString(index, value)
        }

        override fun executeUpdateDelete(): Int = delegate.executeUpdateDelete()

        override fun close() {
            delegate.close()
        }
    }
}

internal fun shouldExecuteViaRawQueryForTest(sql: String): Boolean = shouldExecuteViaRawQuery(sql)

private fun shouldExecuteViaRawQuery(sql: String): Boolean = sql.trimStart().startsWith("PRAGMA", ignoreCase = true)
