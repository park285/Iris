package party.qwer.iris

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IrisServerNettyTransportTest {
    @Test
    fun `enforceNettyNioTransport sets noNative property when absent`() {
        val previous = System.getProperty(NETTY_NO_NATIVE_PROPERTY)
        try {
            System.clearProperty(NETTY_NO_NATIVE_PROPERTY)

            val changed = enforceNettyNioTransport()

            assertTrue(changed)
            assertEquals("true", System.getProperty(NETTY_NO_NATIVE_PROPERTY))
        } finally {
            restoreProperty(previous)
        }
    }

    @Test
    fun `enforceNettyNioTransport keeps existing true property`() {
        val previous = System.getProperty(NETTY_NO_NATIVE_PROPERTY)
        try {
            System.setProperty(NETTY_NO_NATIVE_PROPERTY, "true")

            val changed = enforceNettyNioTransport()

            assertFalse(changed)
            assertEquals("true", System.getProperty(NETTY_NO_NATIVE_PROPERTY))
        } finally {
            restoreProperty(previous)
        }
    }

    private fun restoreProperty(previous: String?) {
        if (previous == null) {
            System.clearProperty(NETTY_NO_NATIVE_PROPERTY)
        } else {
            System.setProperty(NETTY_NO_NATIVE_PROPERTY, previous)
        }
    }
}
