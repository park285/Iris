package party.qwer.iris.ingress

import kotlinx.serialization.json.Json
import party.qwer.iris.IrisLogger
import party.qwer.iris.model.NicknameChangeEvent
import party.qwer.iris.persistence.MemberIdentityStateStore
import party.qwer.iris.persistence.RoomEventStore
import party.qwer.iris.snapshot.SnapshotEventEmitter
import party.qwer.iris.storage.ChatId
import party.qwer.iris.storage.UserId

internal class IngressNicknameTracker(
    private val stateStore: MemberIdentityStateStore,
    private val roomEventStore: RoomEventStore?,
    private val emitter: SnapshotEventEmitter,
    private val serverJson: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
) {
    private val knownNicknames = linkedMapOf<ChatId, MutableMap<UserId, String>>()
    private val lastAlertedNicknames = linkedMapOf<ChatId, MutableMap<UserId, String>>()
    private val lastLoadedEventIdByChat = linkedMapOf<ChatId, Long>()

    init {
        stateStore.loadAll().forEach { (chatId, nicknames) ->
            knownNicknames[chatId] = nicknames.toMutableMap()
        }
    }

    @Synchronized
    fun observe(
        chatId: Long,
        userId: Long,
        linkId: Long?,
        currentNickname: String,
        timestamp: Long,
    ) {
        val resolvedNickname = currentNickname.trim()
        if (resolvedNickname.isBlank() || resolvedNickname == userId.toString()) {
            return
        }

        val roomKey = ChatId(chatId)
        val userKey = UserId(userId)
        val roomState = knownNicknames.getOrPut(roomKey) { linkedMapOf() }
        val alertedNickname = latestAlertedNickname(roomKey, userKey)
        val knownNickname = roomState[userKey]?.trim().orEmpty()
        val previousNickname =
            when {
                alertedNickname == resolvedNickname -> null
                knownNickname.isNotBlank() && knownNickname != resolvedNickname -> knownNickname
                !alertedNickname.isNullOrBlank() && alertedNickname != resolvedNickname -> alertedNickname
                else -> null
            }

        if (previousNickname.isNullOrBlank()) {
            roomState[userKey] = resolvedNickname
            stateStore.save(roomKey, roomState)
            return
        }

        runCatching {
            emitter.emit(
                listOf(
                    NicknameChangeEvent(
                        chatId = chatId,
                        linkId = linkId,
                        userId = userId,
                        oldNickname = previousNickname,
                        newNickname = resolvedNickname,
                        timestamp = timestamp,
                    ),
                ),
            )
        }.onSuccess {
            roomState[userKey] = resolvedNickname
            stateStore.save(roomKey, roomState)
            rememberAlertedNickname(roomKey, userKey, resolvedNickname)
        }.onFailure { error ->
            IrisLogger.error(
                "[IngressNicknameTracker] Failed to emit nickname change chatId=$chatId, userId=$userId: ${error.message}",
                error,
            )
        }
    }

    private fun latestAlertedNickname(
        chatId: ChatId,
        userId: UserId,
    ): String? {
        refreshAlertHistory(chatId)
        return lastAlertedNicknames.getOrPut(chatId) { linkedMapOf() }[userId]
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun rememberAlertedNickname(
        chatId: ChatId,
        userId: UserId,
        nickname: String,
    ) {
        if (nickname.isBlank()) {
            return
        }

        lastAlertedNicknames.getOrPut(chatId) { linkedMapOf() }[userId] = nickname
    }

    private fun refreshAlertHistory(chatId: ChatId) {
        val store = roomEventStore ?: return
        val roomHistory = lastAlertedNicknames.getOrPut(chatId) { linkedMapOf() }
        var afterId = lastLoadedEventIdByChat[chatId] ?: 0L
        while (true) {
            val records = store.listByChatId(chatId.value, limit = NICKNAME_HISTORY_SCAN_LIMIT, afterId = afterId)
            if (records.isEmpty()) {
                break
            }

            records.forEach { record ->
                if (record.eventType != "nickname_change") {
                    return@forEach
                }

                val nickname =
                    runCatching {
                        serverJson.decodeFromString(NicknameChangeEvent.serializer(), record.payload).newNickname?.trim()
                    }.getOrNull()
                if (!nickname.isNullOrBlank()) {
                    roomHistory[UserId(record.userId)] = nickname
                }
            }
            afterId = records.last().id
        }
        lastLoadedEventIdByChat[chatId] = afterId
    }

    private companion object {
        const val NICKNAME_HISTORY_SCAN_LIMIT = 2_000
    }
}
