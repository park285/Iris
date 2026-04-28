package party.qwer.iris

import party.qwer.iris.model.RoomListResponse
import party.qwer.iris.model.RoomSummary
import party.qwer.iris.storage.ChatId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatRoomRefreshSchedulerTest {
    @Test
    fun `chatroom refresh targets include only open chat rooms`() {
        val targets =
            chatRoomRefreshRoomIds(
                RoomListResponse(
                    rooms =
                        listOf(
                            room(chatId = 1L, linkId = 10L),
                            room(chatId = 2L, linkId = null),
                            room(chatId = 3L, linkId = 30L),
                            room(chatId = 0L, linkId = 40L),
                        ),
                ),
            )

        assertEquals(listOf(ChatId(1L), ChatId(3L)), targets)
    }

    @Test
    fun `refreshOnce opens distinct positive room ids in order`() {
        val opened = mutableListOf<Long>()
        val sleeps = mutableListOf<Long>()
        val scheduler =
            ChatRoomRefreshScheduler(
                enabled = true,
                refreshIntervalMs = 60_000L,
                openDelayMs = 250L,
                roomIdProvider = { listOf(ChatId(3L), ChatId(1L), ChatId(3L), ChatId(0L)) },
                chatRoomOpener = { roomId ->
                    opened += roomId
                    ImageBridgeResult(success = true)
                },
                sleeper = { delayMs -> sleeps += delayMs },
            )

        val result = scheduler.refreshOnce()

        assertEquals(listOf(3L, 1L), opened)
        assertEquals(listOf(250L), sleeps)
        assertEquals(2, result.attempted)
        assertEquals(2, result.opened)
        assertEquals(0, result.failed)
        assertFalse(result.skipped)
    }

    @Test
    fun `refreshOnce is skipped when disabled`() {
        var opened = false
        val scheduler =
            ChatRoomRefreshScheduler(
                enabled = false,
                refreshIntervalMs = 60_000L,
                openDelayMs = 250L,
                roomIdProvider = { listOf(ChatId(3L)) },
                chatRoomOpener = {
                    opened = true
                    ImageBridgeResult(success = true)
                },
            )

        val result = scheduler.refreshOnce()

        assertFalse(opened)
        assertTrue(result.skipped)
        assertEquals(0, result.attempted)
    }

    @Test
    fun `refreshOnce continues after a room open failure`() {
        val opened = mutableListOf<Long>()
        val scheduler =
            ChatRoomRefreshScheduler(
                enabled = true,
                refreshIntervalMs = 60_000L,
                openDelayMs = 1L,
                roomIdProvider = { listOf(ChatId(1L), ChatId(2L), ChatId(3L)) },
                chatRoomOpener = { roomId ->
                    opened += roomId
                    if (roomId == 2L) {
                        ImageBridgeResult(success = false, error = "open failed")
                    } else {
                        ImageBridgeResult(success = true)
                    }
                },
                sleeper = {},
                logWarn = { _ -> },
            )

        val result = scheduler.refreshOnce()

        assertEquals(listOf(1L, 2L, 3L), opened)
        assertEquals(3, result.attempted)
        assertEquals(2, result.opened)
        assertEquals(1, result.failed)
    }

    private fun room(
        chatId: Long,
        linkId: Long?,
    ): RoomSummary =
        RoomSummary(
            chatId = chatId,
            type = null,
            linkId = linkId,
            activeMembersCount = null,
        )
}
