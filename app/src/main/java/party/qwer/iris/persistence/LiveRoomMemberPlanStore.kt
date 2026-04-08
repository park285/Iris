@file:Suppress("ProguardSerializableOutsideModel")

package party.qwer.iris.persistence

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import party.qwer.iris.LiveRoomMemberExtractionPlan
import party.qwer.iris.storage.ChatId
import java.io.Closeable

internal interface LiveRoomMemberPlanStore : Closeable {
    fun loadAll(): Map<ChatId, StoredLiveRoomMemberPlan>

    fun save(
        chatId: ChatId,
        state: StoredLiveRoomMemberPlan,
    )

    fun remove(chatId: ChatId)
}

@Serializable
internal data class StoredLiveRoomMemberPlan(
    val plan: LiveRoomMemberExtractionPlan,
    val lastKnownMembers: List<StoredLiveRoomMemberIdentity> = emptyList(),
)

@Serializable
internal data class StoredLiveRoomMemberIdentity(
    val userId: Long,
    val nickname: String? = null,
)

internal class InMemoryLiveRoomMemberPlanStore : LiveRoomMemberPlanStore {
    private val states = linkedMapOf<ChatId, StoredLiveRoomMemberPlan>()

    override fun loadAll(): Map<ChatId, StoredLiveRoomMemberPlan> = states.toMap()

    override fun save(
        chatId: ChatId,
        state: StoredLiveRoomMemberPlan,
    ) {
        states[chatId] = state
    }

    override fun remove(chatId: ChatId) {
        states.remove(chatId)
    }

    override fun close() = Unit
}

internal class SqliteLiveRoomMemberPlanStore(
    private val db: SqliteDriver,
    private val clock: () -> Long = System::currentTimeMillis,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : LiveRoomMemberPlanStore {
    override fun loadAll(): Map<ChatId, StoredLiveRoomMemberPlan> =
        db
            .query(
                "SELECT chat_id, plan_json, members_json FROM ${IrisDatabaseSchema.LIVE_ROOM_MEMBER_PLAN_TABLE} ORDER BY chat_id",
            ) { row ->
                val chatId = ChatId(row.getLong(0))
                chatId to
                    StoredLiveRoomMemberPlan(
                        plan = json.decodeFromString(row.getString(1)),
                        lastKnownMembers = json.decodeFromString(row.getString(2)),
                    )
            }.toMap()

    override fun save(
        chatId: ChatId,
        state: StoredLiveRoomMemberPlan,
    ) {
        db.update(
            """
            INSERT INTO ${IrisDatabaseSchema.LIVE_ROOM_MEMBER_PLAN_TABLE} (chat_id, plan_json, members_json, updated_at)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(chat_id) DO UPDATE SET
                plan_json = excluded.plan_json,
                members_json = excluded.members_json,
                updated_at = excluded.updated_at
            """.trimIndent(),
            listOf(
                chatId.value,
                json.encodeToString(state.plan),
                json.encodeToString(state.lastKnownMembers),
                clock(),
            ),
        )
    }

    override fun remove(chatId: ChatId) {
        db.update(
            "DELETE FROM ${IrisDatabaseSchema.LIVE_ROOM_MEMBER_PLAN_TABLE} WHERE chat_id = ?",
            listOf(chatId.value),
        )
    }

    override fun close() = Unit
}
