package party.qwer.iris.persistence

import party.qwer.iris.RoomSnapshotData
import party.qwer.iris.storage.ChatId
import party.qwer.iris.storage.LinkId
import party.qwer.iris.storage.UserId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SqliteSnapshotStateStoreTest {
    @Test
    fun `present snapshot round-trips through sqlite store`() {
        JdbcSqliteHelper.inMemory().use { driver ->
            IrisDatabaseSchema.createAll(driver)
            val store = SqliteSnapshotStateStore(driver)
            val snapshot =
                RoomSnapshotData(
                    chatId = ChatId(42L),
                    linkId = LinkId(99L),
                    memberIds = setOf(UserId(1L), UserId(2L)),
                    blindedIds = setOf(UserId(3L)),
                    nicknames = mapOf(UserId(1L) to "Alice", UserId(2L) to "Bob"),
                    roles = mapOf(UserId(1L) to 1, UserId(2L) to 2),
                    profileImages = mapOf(UserId(1L) to "alice.png"),
                )

            store.savePresent(snapshot)

            assertEquals(PersistedSnapshotState.Present(snapshot), store.loadAll()[ChatId(42L)])
        }
    }

    @Test
    fun `remove deletes persisted snapshot state`() {
        JdbcSqliteHelper.inMemory().use { driver ->
            IrisDatabaseSchema.createAll(driver)
            val store = SqliteSnapshotStateStore(driver)

            store.saveMissing(ChatId(77L))
            store.remove(ChatId(77L))

            assertNull(store.loadAll()[ChatId(77L)])
        }
    }
}
