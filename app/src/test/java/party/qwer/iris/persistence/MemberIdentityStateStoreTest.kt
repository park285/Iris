package party.qwer.iris.persistence

import party.qwer.iris.storage.ChatId
import party.qwer.iris.storage.UserId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MemberIdentityStateStoreTest {
    @Test
    fun `in memory store round trips nicknames`() {
        val store = InMemoryMemberIdentityStateStore()
        val nicknames = mapOf(UserId(1L) to "Alice", UserId(2L) to "Bob")

        store.save(ChatId(42L), nicknames)

        assertEquals(nicknames, store.loadAll()[ChatId(42L)])
    }

    @Test
    fun `sqlite store round trips nicknames`() {
        JdbcSqliteHelper.inMemory().use { driver ->
            IrisDatabaseSchema.createAll(driver)
            val store = SqliteMemberIdentityStateStore(driver)
            val nicknames = mapOf(UserId(1L) to "Alice", UserId(2L) to "Bob")

            store.save(ChatId(42L), nicknames)

            assertEquals(nicknames, store.loadAll()[ChatId(42L)])
        }
    }

    @Test
    fun `remove deletes persisted nickname state`() {
        JdbcSqliteHelper.inMemory().use { driver ->
            IrisDatabaseSchema.createAll(driver)
            val store = SqliteMemberIdentityStateStore(driver)

            store.save(ChatId(42L), mapOf(UserId(1L) to "Alice"))
            store.remove(ChatId(42L))

            assertNull(store.loadAll()[ChatId(42L)])
        }
    }
}
