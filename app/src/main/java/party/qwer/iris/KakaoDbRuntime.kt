package party.qwer.iris

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import java.io.Closeable
import java.io.File

internal class KakaoDbRuntime(
    private val config: ConfigManager,
    private val dbPath: String = "${PathUtils.getAppPath()}databases",
    irisMetadataDbPath: String = "$dbPath/iris.db",
) : Closeable {
    private val primaryConnection: SQLiteDatabase
    private val primaryLock = Any()
    private val readConnectionLock = Any()
    private val irisMetadataDbFile = File(irisMetadataDbPath)

    @Volatile
    private var readConnection: SQLiteDatabase? = null

    init {
        try {
            val openedConnection =
                SQLiteDatabase.openDatabase(
                    "$dbPath/KakaoTalk.db",
                    null,
                    SQLiteDatabase.OPEN_READONLY,
                )
            attachAuxiliaryDatabases(openedConnection)
            primaryConnection = openedConnection
            when (val resolution = resolveBotUserId(detectBotUserId(primaryConnection), config.botId)) {
                is BotUserIdResolution.Detected -> {
                    IrisLogger.info("Bot user_id is detected from chat_logs: ${resolution.botId}")
                    config.botId = resolution.botId
                }

                is BotUserIdResolution.ConfigFallback -> {
                    IrisLogger.info("Using configured bot user_id fallback: ${resolution.botId}")
                }

                BotUserIdResolution.Missing -> {
                    IrisLogger.error(
                        "Warning: Bot user_id not found in chat_logs and no configured fallback exists. " +
                            "Decryption might not work correctly.",
                    )
                }
            }
        } catch (e: SQLiteException) {
            IrisLogger.error("SQLiteException: ${e.message}", e)
            throw IllegalStateException("You don't have a permission to access KakaoTalk Database.", e)
        }
    }

    fun <T> withPrimaryConnection(block: (SQLiteDatabase) -> T): T =
        synchronized(primaryLock) {
            block(primaryConnection)
        }

    fun <T> withReadConnection(block: (SQLiteDatabase) -> T): T =
        synchronized(readConnectionLock) {
            block(getOrCreateReadConnection())
        }

    fun openDetachedReadConnection(): SQLiteDatabase =
        SQLiteDatabase
            .openDatabase(
                "$dbPath/KakaoTalk.db",
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).also(::attachAuxiliaryDatabases)

    override fun close() {
        synchronized(readConnectionLock) {
            readConnection?.let { conn ->
                if (conn.isOpen) {
                    conn.close()
                }
                readConnection = null
            }
        }
        synchronized(primaryLock) {
            if (primaryConnection.isOpen) {
                primaryConnection.close()
            }
        }
    }

    private fun getOrCreateReadConnection(): SQLiteDatabase =
        readConnection ?: synchronized(readConnectionLock) {
            readConnection ?: openDetachedReadConnection().also { readConnection = it }
        }

    private fun attachAuxiliaryDatabases(db: SQLiteDatabase) {
        db.execSQL("ATTACH DATABASE '$dbPath/KakaoTalk2.db' AS db2")
        if (irisMetadataDbFile.exists()) {
            db.execSQL("ATTACH DATABASE '${irisMetadataDbFile.absolutePath}' AS db3")
        }
    }
}
