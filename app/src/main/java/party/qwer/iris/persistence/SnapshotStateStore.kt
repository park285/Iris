package party.qwer.iris.persistence

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import party.qwer.iris.RoomSnapshotData
import party.qwer.iris.model.PersistedSnapshotPayload
import party.qwer.iris.model.PersistedSnapshotRoleEntry
import party.qwer.iris.model.PersistedSnapshotStringEntry
import party.qwer.iris.storage.ChatId
import party.qwer.iris.storage.LinkId
import party.qwer.iris.storage.UserId
import java.io.Closeable

internal sealed interface PersistedSnapshotState {
    val chatId: ChatId

    data class Present(
        val snapshot: RoomSnapshotData,
    ) : PersistedSnapshotState {
        override val chatId: ChatId
            get() = snapshot.chatId
    }

    data class Missing(
        override val chatId: ChatId,
    ) : PersistedSnapshotState
}

internal interface SnapshotStateStore : Closeable {
    fun loadAll(): Map<ChatId, PersistedSnapshotState>

    fun savePresent(snapshot: RoomSnapshotData)

    fun saveMissing(chatId: ChatId)

    fun remove(chatId: ChatId)
}

internal class InMemorySnapshotStateStore : SnapshotStateStore {
    private val states = linkedMapOf<ChatId, PersistedSnapshotState>()

    override fun loadAll(): Map<ChatId, PersistedSnapshotState> = states.toMap()

    override fun savePresent(snapshot: RoomSnapshotData) {
        states[snapshot.chatId] = PersistedSnapshotState.Present(snapshot)
    }

    override fun saveMissing(chatId: ChatId) {
        states[chatId] = PersistedSnapshotState.Missing(chatId)
    }

    override fun remove(chatId: ChatId) {
        states.remove(chatId)
    }

    override fun close() = Unit
}

internal class SqliteSnapshotStateStore(
    private val db: SqliteDriver,
    private val clock: () -> Long = System::currentTimeMillis,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : SnapshotStateStore {
    override fun loadAll(): Map<ChatId, PersistedSnapshotState> =
        db.query(
            "SELECT chat_id, state, snapshot_json FROM ${IrisDatabaseSchema.SNAPSHOT_STATE_TABLE} ORDER BY chat_id",
        ) { row ->
            val chatId = ChatId(row.getLong(0))
            when (row.getString(1)) {
                STATE_PRESENT ->
                    PersistedSnapshotState.Present(
                        row.getStringOrNull(2)?.let(::decodeSnapshot)
                            ?: error("missing snapshot payload for chatId=${chatId.value}"),
                    )
                STATE_MISSING -> PersistedSnapshotState.Missing(chatId)
                else -> error("unknown snapshot state for chatId=${chatId.value}")
            }
        }.associateBy(PersistedSnapshotState::chatId)

    override fun savePresent(snapshot: RoomSnapshotData) {
        upsert(
            chatId = snapshot.chatId,
            state = STATE_PRESENT,
            snapshotJson = json.encodeToString(snapshotPayloadFrom(snapshot)),
        )
    }

    override fun saveMissing(chatId: ChatId) {
        upsert(chatId = chatId, state = STATE_MISSING, snapshotJson = null)
    }

    override fun remove(chatId: ChatId) {
        db.update(
            "DELETE FROM ${IrisDatabaseSchema.SNAPSHOT_STATE_TABLE} WHERE chat_id = ?",
            listOf(chatId.value),
        )
    }

    override fun close() = Unit

    private fun upsert(
        chatId: ChatId,
        state: String,
        snapshotJson: String?,
    ) {
        val now = clock()
        db.update(
            """
            INSERT INTO ${IrisDatabaseSchema.SNAPSHOT_STATE_TABLE} (chat_id, state, snapshot_json, updated_at)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(chat_id) DO UPDATE SET
                state = excluded.state,
                snapshot_json = excluded.snapshot_json,
                updated_at = excluded.updated_at
            """.trimIndent(),
            listOf(chatId.value, state, snapshotJson, now),
        )
    }

    private fun decodeSnapshot(encoded: String): RoomSnapshotData =
        json.decodeFromString<PersistedSnapshotPayload>(encoded).toSnapshotData()

    private companion object {
        private const val STATE_PRESENT = "PRESENT"
        private const val STATE_MISSING = "MISSING"
    }
}

private fun PersistedSnapshotPayload.toSnapshotData(): RoomSnapshotData =
    RoomSnapshotData(
        chatId = ChatId(chatId),
        linkId = linkId?.let(::LinkId),
        memberIds = memberIds.map(::UserId).toSet(),
        blindedIds = blindedIds.map(::UserId).toSet(),
        nicknames = nicknames.associate { UserId(it.userId) to it.value },
        roles = roles.associate { UserId(it.userId) to it.role },
        profileImages = profileImages.associate { UserId(it.userId) to it.value },
    )

private fun snapshotPayloadFrom(snapshot: RoomSnapshotData): PersistedSnapshotPayload =
    PersistedSnapshotPayload(
        chatId = snapshot.chatId.value,
        linkId = snapshot.linkId?.value,
        memberIds = snapshot.memberIds.map(UserId::value).sorted(),
        blindedIds = snapshot.blindedIds.map(UserId::value).sorted(),
        nicknames =
            snapshot.nicknames.entries
                .sortedBy { it.key.value }
                .map { (userId, value) -> PersistedSnapshotStringEntry(userId.value, value) },
        roles =
            snapshot.roles.entries
                .sortedBy { it.key.value }
                .map { (userId, role) -> PersistedSnapshotRoleEntry(userId.value, role) },
        profileImages =
            snapshot.profileImages.entries
                .sortedBy { it.key.value }
                .map { (userId, value) -> PersistedSnapshotStringEntry(userId.value, value) },
    )
