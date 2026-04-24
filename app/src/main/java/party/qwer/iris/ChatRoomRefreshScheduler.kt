package party.qwer.iris

import party.qwer.iris.model.RoomListResponse
import party.qwer.iris.storage.ChatId
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

internal fun chatRoomRefreshRoomIds(roomList: RoomListResponse): List<ChatId> =
    roomList.rooms
        .asSequence()
        .filter { room -> room.linkId != null }
        .map { room -> room.chatId }
        .filter { chatId -> chatId > 0L }
        .distinct()
        .map(::ChatId)
        .toList()

internal data class ChatRoomRefreshResult(
    val attempted: Int,
    val opened: Int,
    val failed: Int,
    val skipped: Boolean = false,
)

internal class ChatRoomRefreshScheduler(
    private val enabled: Boolean,
    private val refreshIntervalMs: Long,
    private val openDelayMs: Long,
    private val roomIdProvider: () -> List<ChatId>,
    private val chatRoomOpener: (Long) -> ImageBridgeResult,
    private val sleeper: (Long) -> Unit = { delayMs -> Thread.sleep(delayMs) },
    private val logInfo: (String) -> Unit = IrisLogger::info,
    private val logWarn: (String) -> Unit = IrisLogger::warn,
    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "iris-chatroom-refresh").apply {
                isDaemon = true
            }
        },
) {
    @Volatile
    private var started = false

    @Synchronized
    fun start() {
        if (!enabled || started) {
            return
        }
        started = true
        executor.scheduleWithFixedDelay(
            { refreshOnce() },
            0L,
            refreshIntervalMs,
            TimeUnit.MILLISECONDS,
        )
        logInfo("[ChatRoomRefreshScheduler] started intervalMs=$refreshIntervalMs openDelayMs=$openDelayMs")
    }

    fun refreshOnce(): ChatRoomRefreshResult {
        if (!enabled) {
            return ChatRoomRefreshResult(attempted = 0, opened = 0, failed = 0, skipped = true)
        }

        val roomIds =
            roomIdProvider()
                .map { it.value }
                .filter { it > 0L }
                .distinct()
        var opened = 0
        var failed = 0

        roomIds.forEachIndexed { index, roomId ->
            if (index > 0 && openDelayMs > 0L) {
                sleeper(openDelayMs)
            }
            val result =
                runCatching { chatRoomOpener(roomId) }
                    .getOrElse { error ->
                        ImageBridgeResult(success = false, error = error.message ?: error.javaClass.name)
                    }
            if (result.success) {
                opened += 1
            } else {
                failed += 1
                logWarn("[ChatRoomRefreshScheduler] open failed roomId=$roomId error=${result.error ?: "unknown"}")
            }
        }

        val refreshResult =
            ChatRoomRefreshResult(
                attempted = roomIds.size,
                opened = opened,
                failed = failed,
            )
        logInfo(
            "[ChatRoomRefreshScheduler] refresh completed attempted=${refreshResult.attempted} " +
                "opened=${refreshResult.opened} failed=${refreshResult.failed}",
        )
        return refreshResult
    }

    @Synchronized
    fun stop() {
        if (!started) {
            return
        }
        started = false
        executor.shutdownNow()
    }
}
