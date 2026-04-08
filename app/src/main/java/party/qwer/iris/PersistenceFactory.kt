package party.qwer.iris

import android.database.sqlite.SQLiteDatabase
import party.qwer.iris.persistence.AndroidSqliteDriver
import party.qwer.iris.persistence.BatchedCheckpointJournal
import party.qwer.iris.persistence.CheckpointJournal
import party.qwer.iris.persistence.IrisDatabaseSchema
import party.qwer.iris.persistence.LiveRoomMemberPlanStore
import party.qwer.iris.persistence.MemberIdentityStateStore
import party.qwer.iris.persistence.RoomEventStore
import party.qwer.iris.persistence.SnapshotStateStore
import party.qwer.iris.persistence.SqliteCheckpointJournal
import party.qwer.iris.persistence.SqliteDriver
import party.qwer.iris.persistence.SqliteLiveRoomMemberPlanStore
import party.qwer.iris.persistence.SqliteMemberIdentityStateStore
import party.qwer.iris.persistence.SqliteRoomEventStore
import party.qwer.iris.persistence.SqliteSnapshotStateStore
import party.qwer.iris.persistence.SqliteSseEventStore
import party.qwer.iris.persistence.SqliteWebhookDeliveryStore
import party.qwer.iris.persistence.SseEventStore
import party.qwer.iris.persistence.WebhookDeliveryStore
import java.io.File

internal data class PersistenceRuntime(
    val driver: SqliteDriver,
    val webhookOutboxStore: WebhookDeliveryStore,
    val checkpointJournal: CheckpointJournal,
    val snapshotStateStore: SnapshotStateStore,
    val memberIdentityStateStore: MemberIdentityStateStore,
    val liveRoomMemberPlanStore: LiveRoomMemberPlanStore,
    val sseEventStore: SseEventStore,
    val roomEventStore: RoomEventStore,
)

internal object PersistenceFactory {
    private const val DEFAULT_CHECKPOINT_FLUSH_INTERVAL_MS = 5_000L

    fun openAndroidDriver(
        appPathProvider: () -> String = PathUtils::getAppPath,
        openDatabase: (String) -> SQLiteDatabase = { databasePath ->
            SQLiteDatabase.openDatabase(
                databasePath,
                null,
                SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY,
            )
        },
    ): SqliteDriver {
        val databaseFile = File("${appPathProvider()}databases/iris.db")
        databaseFile.parentFile?.mkdirs()
        return AndroidSqliteDriver(openDatabase(databaseFile.absolutePath))
    }

    fun createSqliteRuntime(
        driver: SqliteDriver,
        checkpointFlushIntervalMs: Long = DEFAULT_CHECKPOINT_FLUSH_INTERVAL_MS,
        clock: () -> Long = System::currentTimeMillis,
    ): PersistenceRuntime {
        IrisDatabaseSchema.createAll(driver)
        return PersistenceRuntime(
            driver = driver,
            webhookOutboxStore = SqliteWebhookDeliveryStore(driver),
            checkpointJournal =
                BatchedCheckpointJournal(
                    delegate = SqliteCheckpointJournal(driver, clock),
                    flushIntervalMs = checkpointFlushIntervalMs,
                    clock = clock,
                ),
            snapshotStateStore = SqliteSnapshotStateStore(driver, clock),
            memberIdentityStateStore = SqliteMemberIdentityStateStore(driver, clock),
            liveRoomMemberPlanStore = SqliteLiveRoomMemberPlanStore(driver, clock),
            sseEventStore = SqliteSseEventStore(driver),
            roomEventStore = SqliteRoomEventStore(driver),
        )
    }
}
