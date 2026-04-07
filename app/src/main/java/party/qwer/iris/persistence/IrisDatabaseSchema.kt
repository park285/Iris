package party.qwer.iris.persistence

object IrisDatabaseSchema {
    const val CURRENT_SCHEMA_VERSION = 1
    const val WEBHOOK_OUTBOX_TABLE = "webhook_outbox"
    const val CHECKPOINT_TABLE = "checkpoints"
    const val SNAPSHOT_STATE_TABLE = "snapshot_states"
    const val MEMBER_IDENTITY_STATE_TABLE = "member_identity_states"
    const val SSE_EVENTS_TABLE = "sse_events"
    const val ROOM_EVENTS_TABLE = "room_events"
    private const val SQLITE_JOURNAL_MODE_WAL = "PRAGMA journal_mode=WAL"
    private const val SQLITE_BUSY_TIMEOUT_MS = "PRAGMA busy_timeout=5000"

    private val CREATE_WEBHOOK_OUTBOX =
        """
        CREATE TABLE IF NOT EXISTS $WEBHOOK_OUTBOX_TABLE (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            message_id TEXT NOT NULL UNIQUE,
            room_id INTEGER NOT NULL,
            route TEXT NOT NULL,
            payload_json TEXT NOT NULL,
            status TEXT NOT NULL DEFAULT 'PENDING',
            attempt_count INTEGER NOT NULL DEFAULT 0,
            next_attempt_at INTEGER NOT NULL DEFAULT 0,
            claim_token TEXT,
            claimed_at INTEGER,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL,
            last_error TEXT
        )
        """.trimIndent()

    private val CREATE_WEBHOOK_OUTBOX_INDEX =
        """
        CREATE INDEX IF NOT EXISTS idx_webhook_outbox_ready
        ON $WEBHOOK_OUTBOX_TABLE (status, next_attempt_at, id)
        """.trimIndent()

    private val CREATE_CHECKPOINT =
        """
        CREATE TABLE IF NOT EXISTS $CHECKPOINT_TABLE (
            stream TEXT PRIMARY KEY,
            cursor_value INTEGER NOT NULL,
            updated_at INTEGER NOT NULL
        )
        """.trimIndent()

    private val CREATE_SNAPSHOT_STATE =
        """
        CREATE TABLE IF NOT EXISTS $SNAPSHOT_STATE_TABLE (
            chat_id INTEGER PRIMARY KEY,
            state TEXT NOT NULL,
            snapshot_json TEXT,
            updated_at INTEGER NOT NULL
        )
        """.trimIndent()

    private val CREATE_MEMBER_IDENTITY_STATE =
        """
        CREATE TABLE IF NOT EXISTS $MEMBER_IDENTITY_STATE_TABLE (
            chat_id INTEGER PRIMARY KEY,
            nicknames_json TEXT NOT NULL,
            updated_at INTEGER NOT NULL
        )
        """.trimIndent()

    private val CREATE_SSE_EVENTS =
        """
        CREATE TABLE IF NOT EXISTS $SSE_EVENTS_TABLE (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            event_type TEXT NOT NULL,
            payload TEXT NOT NULL,
            created_at INTEGER NOT NULL
        )
        """.trimIndent()

    private val CREATE_ROOM_EVENTS =
        """
        CREATE TABLE IF NOT EXISTS $ROOM_EVENTS_TABLE (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            chat_id INTEGER NOT NULL,
            event_type TEXT NOT NULL,
            user_id INTEGER NOT NULL,
            payload TEXT NOT NULL,
            created_at INTEGER NOT NULL
        )
        """.trimIndent()

    private val CREATE_ROOM_EVENTS_INDEX =
        """
        CREATE INDEX IF NOT EXISTS idx_room_events_chat_id
        ON $ROOM_EVENTS_TABLE (chat_id, id)
        """.trimIndent()

    private data class MigrationStep(
        val fromVersion: Int,
        val toVersion: Int,
        val apply: SqliteDriver.() -> Unit,
    )

    private val MIGRATIONS =
        listOf(
            MigrationStep(fromVersion = 0, toVersion = 1) {
                ensureCurrentSchema(this)
                execute("PRAGMA user_version = 1")
            },
        )

    fun initializeConnection(driver: SqliteDriver) {
        driver.execute(SQLITE_JOURNAL_MODE_WAL)
        driver.execute(SQLITE_BUSY_TIMEOUT_MS)
    }

    fun createWebhookOutboxTable(driver: SqliteDriver) {
        driver.execute(CREATE_WEBHOOK_OUTBOX)
        driver.execute(CREATE_WEBHOOK_OUTBOX_INDEX)
    }

    fun createCheckpointTable(driver: SqliteDriver) {
        driver.execute(CREATE_CHECKPOINT)
    }

    fun createSnapshotStateTable(driver: SqliteDriver) {
        driver.execute(CREATE_SNAPSHOT_STATE)
    }

    fun createMemberIdentityStateTable(driver: SqliteDriver) {
        driver.execute(CREATE_MEMBER_IDENTITY_STATE)
    }

    fun createSseEventsTable(driver: SqliteDriver) {
        driver.execute(CREATE_SSE_EVENTS)
    }

    fun createRoomEventsTable(driver: SqliteDriver) {
        driver.execute(CREATE_ROOM_EVENTS)
        driver.execute(CREATE_ROOM_EVENTS_INDEX)
    }

    fun createAll(driver: SqliteDriver) {
        initializeConnection(driver)
        val currentVersion = driver.queryLong("PRAGMA user_version")?.toInt() ?: 0
        if (currentVersion > CURRENT_SCHEMA_VERSION) {
            throw IllegalStateException(
                "Persistence schema version $currentVersion is newer than supported version $CURRENT_SCHEMA_VERSION",
            )
        }
        driver.inImmediateTransaction {
            if (currentVersion == CURRENT_SCHEMA_VERSION) {
                ensureCurrentSchema(this)
            } else {
                migrate(this, currentVersion)
            }
        }
    }

    private fun migrate(
        driver: SqliteDriver,
        startingVersion: Int,
    ) {
        var version = startingVersion
        while (version < CURRENT_SCHEMA_VERSION) {
            val step =
                MIGRATIONS.firstOrNull { it.fromVersion == version }
                    ?: throw IllegalStateException("No persistence schema migration path from version $version")
            step.apply(driver)
            version = step.toVersion
        }
    }

    private fun ensureCurrentSchema(driver: SqliteDriver) {
        createWebhookOutboxTable(driver)
        createCheckpointTable(driver)
        createSnapshotStateTable(driver)
        createMemberIdentityStateTable(driver)
        createSseEventsTable(driver)
        createRoomEventsTable(driver)
    }
}
