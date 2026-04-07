package party.qwer.iris.persistence

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import party.qwer.iris.storage.ChatId
import party.qwer.iris.storage.UserId
import java.io.Closeable

internal interface MemberIdentityStateStore : Closeable {
    fun loadAll(): Map<ChatId, Map<UserId, String>>

    fun save(
        chatId: ChatId,
        nicknames: Map<UserId, String>,
    )

    fun remove(chatId: ChatId)
}

internal class InMemoryMemberIdentityStateStore : MemberIdentityStateStore {
    private val states = linkedMapOf<ChatId, Map<UserId, String>>()

    override fun loadAll(): Map<ChatId, Map<UserId, String>> = states.toMap()

    override fun save(
        chatId: ChatId,
        nicknames: Map<UserId, String>,
    ) {
        states[chatId] = nicknames.toMap()
    }

    override fun remove(chatId: ChatId) {
        states.remove(chatId)
    }

    override fun close() = Unit
}

internal class SqliteMemberIdentityStateStore(
    private val db: SqliteDriver,
    private val clock: () -> Long = System::currentTimeMillis,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : MemberIdentityStateStore {
    private val serializer = MapSerializer(String.serializer(), String.serializer())

    override fun loadAll(): Map<ChatId, Map<UserId, String>> =
        db
            .query(
                "SELECT chat_id, nicknames_json FROM ${IrisDatabaseSchema.MEMBER_IDENTITY_STATE_TABLE} ORDER BY chat_id",
            ) { row ->
                val chatId = ChatId(row.getLong(0))
                chatId to decodeNicknames(row.getString(1))
            }.toMap()

    override fun save(
        chatId: ChatId,
        nicknames: Map<UserId, String>,
    ) {
        db.update(
            """
            INSERT INTO ${IrisDatabaseSchema.MEMBER_IDENTITY_STATE_TABLE} (chat_id, nicknames_json, updated_at)
            VALUES (?, ?, ?)
            ON CONFLICT(chat_id) DO UPDATE SET
                nicknames_json = excluded.nicknames_json,
                updated_at = excluded.updated_at
            """.trimIndent(),
            listOf(chatId.value, encodeNicknames(nicknames), clock()),
        )
    }

    override fun remove(chatId: ChatId) {
        db.update(
            "DELETE FROM ${IrisDatabaseSchema.MEMBER_IDENTITY_STATE_TABLE} WHERE chat_id = ?",
            listOf(chatId.value),
        )
    }

    override fun close() = Unit

    private fun encodeNicknames(nicknames: Map<UserId, String>): String =
        json.encodeToString(
            serializer,
            nicknames
                .filterValues(String::isNotBlank)
                .mapKeys { (userId, _) -> userId.value.toString() },
        )

    private fun decodeNicknames(encoded: String): Map<UserId, String> =
        json
            .decodeFromString(serializer, encoded)
            .mapNotNull { (userId, nickname) ->
                userId.toLongOrNull()?.let { UserId(it) }?.let { resolvedUserId ->
                    resolvedUserId to nickname
                }
            }.filter { (_, nickname) ->
                nickname.isNotBlank()
            }.associate { (userId, nickname) ->
                userId to nickname
            }
}
