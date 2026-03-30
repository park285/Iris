package party.qwer.iris.persistence

object IrisDatabaseSchema {
    const val WEBHOOK_OUTBOX_TABLE = "webhook_outbox"
    const val CHECKPOINT_TABLE = "checkpoints"
    const val SNAPSHOT_STATE_TABLE = "snapshot_states"
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

    fun createAll(driver: SqliteDriver) {
        initializeConnection(driver)
        createWebhookOutboxTable(driver)
        createCheckpointTable(driver)
        createSnapshotStateTable(driver)
    }
}
