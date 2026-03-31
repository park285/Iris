package party.qwer.iris.persistence

import party.qwer.iris.RoomSnapshotData
import party.qwer.iris.storage.ChatId
import party.qwer.iris.storage.LinkId
import party.qwer.iris.storage.UserId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SqliteSnapshotStateStoreTest {
    private fun snapshot(chatId: Long): RoomSnapshotData =
        RoomSnapshotData(
            chatId = ChatId(chatId),
            linkId = LinkId(chatId * 10L),
            memberIds = setOf(UserId(1L), UserId(2L)),
            blindedIds = setOf(UserId(3L)),
            nicknames = mapOf(UserId(1L) to "Alice", UserId(2L) to "Bob"),
            roles = mapOf(UserId(1L) to 1, UserId(2L) to 2),
            profileImages = mapOf(UserId(1L) to "alice.png"),
        )

    @Test
    fun `present snapshot round-trips through sqlite store`() {
        JdbcSqliteHelper.inMemory().use { driver ->
            IrisDatabaseSchema.createAll(driver)
            val store = SqliteSnapshotStateStore(driver)
            val snapshot = snapshot(42L)

            store.savePresent(snapshot)

            assertEquals(PersistedSnapshotState.Present(snapshot), store.loadAll()[ChatId(42L)])
        }
    }

    @Test
    fun `remove deletes persisted snapshot state`() {
        JdbcSqliteHelper.inMemory().use { driver ->
            IrisDatabaseSchema.createAll(driver)
            val store = SqliteSnapshotStateStore(driver)

            store.saveMissingConfirmed(snapshot(77L), 100L)
            store.remove(ChatId(77L))

            assertNull(store.loadAll()[ChatId(77L)])
        }
    }

    @Test
    fun `pruneMissingOlderThan removes only stale confirmed sqlite missing states`() {
        JdbcSqliteHelper.inMemory().use { driver ->
            IrisDatabaseSchema.createAll(driver)
            val now = longArrayOf(100L)
            val store = SqliteSnapshotStateStore(driver, clock = { now[0] })

            store.saveMissingConfirmed(snapshot(10L), 100L)
            now[0] = 200L
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
}
