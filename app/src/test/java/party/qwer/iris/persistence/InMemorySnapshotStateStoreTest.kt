package party.qwer.iris.persistence

import party.qwer.iris.RoomSnapshotData
import party.qwer.iris.storage.ChatId
import party.qwer.iris.storage.LinkId
import party.qwer.iris.storage.UserId
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InMemorySnapshotStateStoreTest {
    private fun snapshot(chatId: Long): RoomSnapshotData =
        RoomSnapshotData(
            chatId = ChatId(chatId),
            linkId = LinkId(chatId * 10L),
            memberIds = setOf(UserId(1L)),
            blindedIds = emptySet(),
            nicknames = emptyMap(),
            roles = emptyMap(),
            profileImages = emptyMap(),
        )

    @Test
    fun `pruneMissingOlderThan respects cutoff and preserves newer states`() {
        val clock = AtomicLong(100L)
        val store = InMemorySnapshotStateStore(clock::get)

        store.saveMissingConfirmed(snapshot(10L), 100L)
        clock.set(200L)
        store.saveMissingConfirmed(snapshot(20L), 200L)
        clock.set(300L)
        store.savePresent(snapshot(30L))

        assertEquals(setOf(ChatId(10L)), store.pruneMissingOlderThan(150L))
        assertNull(store.loadAll()[ChatId(10L)])
        assertEquals(
            PersistedSnapshotState.MissingConfirmed(
                previousSnapshot = snapshot(20L),
                confirmedAtMs = 200L,
            ),
            store.loadAll()[ChatId(20L)],
        )
        assertEquals(
            PersistedSnapshotState.Present(
                snapshot(30L),
            ),
            store.loadAll()[ChatId(30L)],
        )
    }

    @Test
    fun `pruneMissingOlderThan preserves pending missing states`() {
        val clock = AtomicLong(100L)
        val store = InMemorySnapshotStateStore(clock::get)

        store.saveMissingConfirmed(snapshot(10L), 100L)
        clock.set(200L)
        store.saveMissingPending(
            previousSnapshot = snapshot(20L),
            firstMissingAtMs = 200L,
            consecutiveMisses = 1,
        )

        val removedChatIds = store.pruneMissingOlderThan(150L)

        assertEquals(setOf(ChatId(10L)), removedChatIds)
        assertNull(store.loadAll()[ChatId(10L)])
        assertEquals(
            PersistedSnapshotState.MissingPending(
                previousSnapshot = snapshot(20L),
                firstMissingAtMs = 200L,
                consecutiveMisses = 1,
            ),
            store.loadAll()[ChatId(20L)],
        )
    }
}
