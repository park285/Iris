package party.qwer.iris

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import party.qwer.iris.model.NicknameChangeEvent
import party.qwer.iris.persistence.MemberIdentityStateStore
import party.qwer.iris.persistence.RoomEventStore
import party.qwer.iris.snapshot.RoomSnapshotReadResult
import party.qwer.iris.snapshot.RoomSnapshotReader
import party.qwer.iris.snapshot.SnapshotEventEmitter
import party.qwer.iris.storage.ChatId
import party.qwer.iris.storage.UserId

internal class MemberIdentityObserver(
    private val roomSnapshotReader: RoomSnapshotReader,
    private val emitter: SnapshotEventEmitter,
    private val stateStore: MemberIdentityStateStore,
    private val roomEventStore: RoomEventStore? = null,
    private val intervalMs: Long,
    private val clock: () -> Long = System::currentTimeMillis,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val coroutineScope = CoroutineScope(SupervisorJob() + dispatcher)
    private val knownNicknames = linkedMapOf<ChatId, Map<UserId, String>>()
    private val lastAlertedNicknames = linkedMapOf<ChatId, MutableMap<UserId, String>>()
    private val lastLoadedEventIdByChat = linkedMapOf<ChatId, Long>()
    private val serverJson =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    @Volatile
    private var job: Job? = null

    @Synchronized
    fun start() {
        if (job?.isActive == true) {
            return
        }

        knownNicknames.clear()
        knownNicknames.putAll(stateStore.loadAll())
        primeMissingRooms()

        job =
            coroutineScope.launch {
                while (isActive) {
                    try {
                        pollAllRooms()
                    } catch (error: Exception) {
                        IrisLogger.error("[MemberIdentityObserver] error: ${error.message}", error)
                    }
                    delay(intervalMs.takeIf { it > 0L } ?: 100L)
                }
            }

        IrisLogger.info(
            "[MemberIdentityObserver] started (intervalMs=$intervalMs)",
        )
    }

    private fun primeMissingRooms() {
        val roomIds = roomSnapshotReader.listRoomChatIds().filter { it.value > 0L }.distinct()
        if (roomIds.isEmpty()) {
            return
        }

        roomIds.forEach { roomId ->
            if (knownNicknames.containsKey(roomId)) {
                return@forEach
            }

            runCatching {
                when (val result = roomSnapshotReader.snapshot(roomId)) {
                    is RoomSnapshotReadResult.Present -> {
                        val currentNicknames = mergedNicknames(result.snapshot)
                        knownNicknames[roomId] = currentNicknames
                        stateStore.save(roomId, currentNicknames)
                    }
                    RoomSnapshotReadResult.Missing -> Unit
                }
            }.onFailure { error ->
                IrisLogger.error(
                    "[MemberIdentityObserver] prime failed chatId=${roomId.value}: ${error.message}",
                    error,
                )
            }
        }
    }

    fun stop() {
        runBlocking { stopSuspend() }
    }

    suspend fun stopSuspend() {
        val captured =
            synchronized(this) {
                val current = job ?: return
                job = null
                current
            }
        captured.cancelAndJoin()
        IrisLogger.info("[MemberIdentityObserver] stopped")
    }

    private fun pollBatch() {
        pollAllRooms()
    }

    private fun pollAllRooms() {
        val roomIds = roomSnapshotReader.listRoomChatIds().filter { it.value > 0L }.distinct()
        if (roomIds.isEmpty()) {
            return
        }

        roomIds.forEach { roomId ->
            runCatching {
                when (val result = roomSnapshotReader.snapshot(roomId)) {
                    is RoomSnapshotReadResult.Present -> handlePresentSnapshot(result.snapshot)
                    RoomSnapshotReadResult.Missing -> Unit
                }
            }.onFailure { error ->
                IrisLogger.error(
                    "[MemberIdentityObserver] room poll failed chatId=${roomId.value}: ${error.message}",
                    error,
                )
            }
        }
    }

    private fun handlePresentSnapshot(snapshot: RoomSnapshotData) {
        val currentNicknames = mergedNicknames(snapshot)
        val events = detectNicknameChanges(snapshot, currentNicknames)
        if (events.isEmpty()) {
            knownNicknames[snapshot.chatId] = currentNicknames
            stateStore.save(snapshot.chatId, currentNicknames)
            return
        }

        runCatching {
            emitter.emit(events)
        }.onSuccess {
            events.forEach { event ->
                event.newNickname?.let { nickname ->
                    rememberAlertedNickname(snapshot.chatId, UserId(event.userId), nickname)
                }
            }
            knownNicknames[snapshot.chatId] = currentNicknames
            stateStore.save(snapshot.chatId, currentNicknames)
        }.onFailure { error ->
            IrisLogger.error(
                "[MemberIdentityObserver] Failed to emit nickname changes chatId=${snapshot.chatId.value}: ${error.message}",
                error,
            )
        }
    }

    private fun detectNicknameChanges(
        snapshot: RoomSnapshotData,
        current: Map<UserId, String>,
    ): List<NicknameChangeEvent> {
        val previous = knownNicknames[snapshot.chatId].orEmpty()
        val timestamp = clock() / 1000

        return current.keys
            .mapNotNull { userId ->
                val newNickname = current[userId]
                if (newNickname.isNullOrBlank()) {
                    return@mapNotNull null
                }

                val alertedNickname = latestAlertedNickname(snapshot.chatId, userId)
                val previousNickname = previous[userId]?.trim().orEmpty()
                val oldNickname =
                    when {
                        alertedNickname == newNickname -> null
                        previousNickname.isNotBlank() && previousNickname != newNickname -> previousNickname
                        !alertedNickname.isNullOrBlank() && alertedNickname != newNickname -> alertedNickname
                        else -> null
                    }

                if (oldNickname.isNullOrBlank()) {
                    return@mapNotNull null
                }

                NicknameChangeEvent(
                    chatId = snapshot.chatId.value,
                    linkId = snapshot.linkId?.value,
                    userId = userId.value,
                    oldNickname = oldNickname,
                    newNickname = newNickname,
                    timestamp = timestamp,
                )
            }
    }

    private fun mergedNicknames(snapshot: RoomSnapshotData): Map<UserId, String> {
        val previous = knownNicknames[snapshot.chatId].orEmpty()
        val current = normalizedNicknames(snapshot.nicknames)
        val observedUserIds =
            linkedSetOf<UserId>().apply {
                addAll(snapshot.memberIds)
                // open member 닉네임이 먼저 보이면 room.members 지연과 무관하게 추적한다.
                addAll(current.keys)
            }
        val merged = linkedMapOf<UserId, String>()
        observedUserIds.forEach { userId ->
            val nickname = current[userId] ?: previous[userId]
            if (!nickname.isNullOrBlank()) {
                merged[userId] = nickname
            }
        }
        return merged
    }

    private fun latestAlertedNickname(
        chatId: ChatId,
        userId: UserId,
    ): String? {
        refreshAlertHistory(chatId)
        return lastAlertedNicknames
            .getOrPut(chatId) { linkedMapOf() }[userId]
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

    private fun normalizedNicknames(nicknames: Map<UserId, String>): Map<UserId, String> =
        nicknames
            .mapValues { (_, nickname) -> nickname.trim() }
            .filterValues { nickname -> nickname.isNotBlank() }

    private companion object {
        const val NICKNAME_HISTORY_SCAN_LIMIT = 2_000
    }
}
