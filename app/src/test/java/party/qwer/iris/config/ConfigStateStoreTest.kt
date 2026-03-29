package party.qwer.iris.config

import party.qwer.iris.DiscoveredConfigState
import party.qwer.iris.UserConfigState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfigStateStoreTest {
    @Test
    fun `initial state has default values`() {
        val store = ConfigStateStore()
        val state = store.current()
        assertEquals(UserConfigState(), state.snapshotUser)
        assertEquals(UserConfigState(), state.appliedUser)
        assertEquals(DiscoveredConfigState(), state.discovered)
        assertFalse(state.isDirty)
    }

    @Test
    fun `mutate updates state and returns new state`() {
        val store = ConfigStateStore()
        val result = store.mutate { it.copy(isDirty = true) }
        assertTrue(result.isDirty)
        assertTrue(store.current().isDirty)
    }

    @Test
    fun `mutate is atomic under concurrent access`() {
        val store = ConfigStateStore()
        val iterations = 1000
        val threads =
            (1..4).map {
                Thread {
                    repeat(iterations) {
                        store.mutate { current ->
                            val newPort = current.snapshotUser.botHttpPort + 1
                            current.copy(
                                snapshotUser = current.snapshotUser.copy(botHttpPort = newPort),
                            )
                        }
                    }
                }
            }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertEquals(4 * iterations, store.current().snapshotUser.botHttpPort - 3000)
    }

    @Test
    fun `current reads without lock`() {
        val store = ConfigStateStore()
        store.mutate { it.copy(snapshotUser = it.snapshotUser.copy(botName = "Test")) }
        assertEquals("Test", store.current().snapshotUser.botName)
    }

    @Test
    fun `updateUserState with applyImmediately updates both snapshot and applied`() {
        val store = ConfigStateStore()
        store.updateUserState(applyImmediately = true) { it.copy(messageSendRate = 200) }
        assertEquals(200L, store.current().snapshotUser.messageSendRate)
        assertEquals(200L, store.current().appliedUser.messageSendRate)
        assertTrue(store.current().isDirty)
    }

    @Test
    fun `updateUserState without applyImmediately updates only snapshot`() {
        val store = ConfigStateStore()
        store.updateUserState(applyImmediately = false) { it.copy(botHttpPort = 4000) }
        assertEquals(4000, store.current().snapshotUser.botHttpPort)
        assertEquals(3000, store.current().appliedUser.botHttpPort)
        assertTrue(store.current().isDirty)
    }

    @Test
    fun `updateUserState with no-op does not mark dirty`() {
        val store = ConfigStateStore()
        store.updateUserState(applyImmediately = true) { it }
        assertFalse(store.current().isDirty)
    }

    @Test
    fun `clearDirty resets dirty flag`() {
        val store = ConfigStateStore()
        store.mutate { it.copy(isDirty = true) }
        assertTrue(store.current().isDirty)
        store.clearDirty()
        assertFalse(store.current().isDirty)
    }
}
