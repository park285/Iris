package party.qwer.iris.persistence

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import party.qwer.iris.RoomSnapshotData
import party.qwer.iris.model.PersistedMissingSnapshotPayload
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

    data class MissingPending(
        val previousSnapshot: RoomSnapshotData,
        val firstMissingAtMs: Long,
        val consecutiveMisses: Int,
    ) : PersistedSnapshotState {
        override val chatId: ChatId
            get() = previousSnapshot.chatId
    }

    data class MissingConfirmed(
        val previousSnapshot: RoomSnapshotData,
        val confirmedAtMs: Long,
    ) : PersistedSnapshotState {
        override val chatId: ChatId
            get() = previousSnapshot.chatId
    }
}

internal interface SnapshotStateStore : Closeable {
    fun loadAll(): Map<ChatId, PersistedSnapshotState>

    fun savePresent(snapshot: RoomSnapshotData)

    fun saveMissingPending(
        previousSnapshot: RoomSnapshotData,
        firstMissingAtMs: Long,
        consecutiveMisses: Int,
    )

    fun saveMissingConfirmed(
        previousSnapshot: RoomSnapshotData,
        confirmedAtMs: Long,
    )

    fun pruneMissingOlderThan(cutoffEpochMs: Long): Set<ChatId>

    fun remove(chatId: ChatId)
}

internal class InMemorySnapshotStateStore(
    private val clock: () -> Long = System::currentTimeMillis,
) : SnapshotStateStore {
    private val states = linkedMapOf<ChatId, PersistedSnapshotState>()

    override fun loadAll(): Map<ChatId, PersistedSnapshotState> = states.toMap()

    override fun savePresent(snapshot: RoomSnapshotData) {
        states[snapshot.chatId] = PersistedSnapshotState.Present(snapshot)
    }

    override fun saveMissingPending(
        previousSnapshot: RoomSnapshotData,
        firstMissingAtMs: Long,
        consecutiveMisses: Int,
    ) {
        states[previousSnapshot.chatId] =
            PersistedSnapshotState.MissingPending(
                previousSnapshot = previousSnapshot,
                firstMissingAtMs = firstMissingAtMs,
                consecutiveMisses = consecutiveMisses,
            )
    }

    override fun saveMissingConfirmed(
        previousSnapshot: RoomSnapshotData,
        confirmedAtMs: Long,
    ) {
        states[previousSnapshot.chatId] =
            PersistedSnapshotState.MissingConfirmed(
                previousSnapshot = previousSnapshot,
                confirmedAtMs = confirmedAtMs,
            )
    }

    override fun pruneMissingOlderThan(cutoffEpochMs: Long): Set<ChatId> {
        val staleChatIds =
            states.entries
                .filter { (_, state) ->
                    state is PersistedSnapshotState.MissingConfirmed &&
                        state.confirmedAtMs < cutoffEpochMs
                }.map { it.key }
                .toSet()
        staleChatIds.forEach(states::remove)
        return staleChatIds
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
        db
            .query(
                "SELECT chat_id, state, snapshot_json, updated_at FROM ${IrisDatabaseSchema.SNAPSHOT_STATE_TABLE} ORDER BY chat_id",
            ) { row ->
                val chatId = ChatId(row.getLong(0))
                when (row.getString(1)) {
                    STATE_PRESENT ->
                        PersistedSnapshotState.Present(
                            row.getStringOrNull(2)?.let(::decodeSnapshot)
                                ?: error("missing snapshot payload for chatId=${chatId.value}"),
                        )

                    STATE_MISSING_PENDING ->
                        row.getStringOrNull(2)?.let(::decodeMissingSnapshotPending)
                            ?: error("missing pending snapshot payload for chatId=${chatId.value}")

                    STATE_MISSING_CONFIRMED ->
                        row.getStringOrNull(2)?.let(::decodeMissingSnapshotConfirmed)
                            ?: error("missing confirmed snapshot payload for chatId=${chatId.value}")

                    else -> error("unknown snapshot state for chatId=${chatId.value}")
                }
            }.associateBy(PersistedSnapshotState::chatId)

    override fun savePresent(snapshot: RoomSnapshotData) {
        upsert(
            chatId = snapshot.chatId,
            state = STATE_PRESENT,
            snapshotJson = json.encodeToString(snapshotPayloadFrom(snapshot)),
            updatedAt = clock(),
        )
    }

    override fun saveMissingPending(
        previousSnapshot: RoomSnapshotData,
        firstMissingAtMs: Long,
        consecutiveMisses: Int,
    ) {
        upsert(
            chatId = previousSnapshot.chatId,
            state = STATE_MISSING_PENDING,
            snapshotJson =
                json.encodeToString(
                    PersistedMissingSnapshotPayload(
                        snapshot = snapshotPayloadFrom(previousSnapshot),
                        firstMissingAtMs = firstMissingAtMs,
                        consecutiveMisses = consecutiveMisses,
                    ),
                ),
            updatedAt = firstMissingAtMs,
        )
    }

    override fun saveMissingConfirmed(
        previousSnapshot: RoomSnapshotData,
        confirmedAtMs: Long,
    ) {
        upsert(
            chatId = previousSnapshot.chatId,
            state = STATE_MISSING_CONFIRMED,
            snapshotJson =
                json.encodeToString(
                    PersistedMissingSnapshotPayload(
                        snapshot = snapshotPayloadFrom(previousSnapshot),
                        confirmedAtMs = confirmedAtMs,
                    ),
                ),
            updatedAt = confirmedAtMs,
        )
    }

    override fun pruneMissingOlderThan(cutoffEpochMs: Long): Set<ChatId> {
        val removedChatIds =
            db
                .query(
                    """
                    SELECT chat_id FROM ${IrisDatabaseSchema.SNAPSHOT_STATE_TABLE}
                    WHERE state = ? AND updated_at < ?
                    ORDER BY chat_id
                    """.trimIndent(),
                    listOf(STATE_MISSING_CONFIRMED, cutoffEpochMs),
                ) { row ->
                    ChatId(row.getLong(0))
                }.toSet()
        if (removedChatIds.isEmpty()) {
            return emptySet()
        }
        db.update(
            """
            DELETE FROM ${IrisDatabaseSchema.SNAPSHOT_STATE_TABLE}
            WHERE state = ? AND updated_at < ?
            """.trimIndent(),
            listOf(STATE_MISSING_CONFIRMED, cutoffEpochMs),
        )
        return removedChatIds
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
        updatedAt: Long,
    ) {
        db.update(
            """
            INSERT INTO ${IrisDatabaseSchema.SNAPSHOT_STATE_TABLE} (chat_id, state, snapshot_json, updated_at)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(chat_id) DO UPDATE SET
                state = excluded.state,
                snapshot_json = excluded.snapshot_json,
                updated_at = excluded.updated_at
            """.trimIndent(),
            listOf(chatId.value, state, snapshotJson, updatedAt),
        )
    }

    private fun decodeSnapshot(encoded: String): RoomSnapshotData = json.decodeFromString<PersistedSnapshotPayload>(encoded).toSnapshotData()

    private fun decodeMissingSnapshotPending(encoded: String): PersistedSnapshotState.MissingPending {
        val payload = json.decodeFromString<PersistedMissingSnapshotPayload>(encoded)
        return PersistedSnapshotState.MissingPending(
            previousSnapshot = payload.snapshot.toSnapshotData(),
            firstMissingAtMs = payload.firstMissingAtMs ?: error("missing firstMissingAtMs in pending snapshot payload"),
            consecutiveMisses = payload.consecutiveMisses.coerceAtLeast(1),
        )
    }

    private fun decodeMissingSnapshotConfirmed(encoded: String): PersistedSnapshotState.MissingConfirmed {
        val payload = json.decodeFromString<PersistedMissingSnapshotPayload>(encoded)
        return PersistedSnapshotState.MissingConfirmed(
            previousSnapshot = payload.snapshot.toSnapshotData(),
            confirmedAtMs = payload.confirmedAtMs ?: error("missing confirmedAtMs in confirmed snapshot payload"),
        )
    }

    private companion object {
        private const val STATE_PRESENT = "PRESENT"
        private const val STATE_MISSING_PENDING = "MISSING_PENDING"
        private const val STATE_MISSING_CONFIRMED = "MISSING_CONFIRMED"
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
