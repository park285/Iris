package party.qwer.iris

import kotlin.test.Test
import kotlin.test.assertEquals

class IrisLoggerTest {
    @Test
    fun `defaults to error when log level is missing or invalid`() {
        assertEquals(IrisLogger.Level.ERROR, parseIrisLogLevel(null))
        assertEquals(IrisLogger.Level.ERROR, parseIrisLogLevel(""))
        assertEquals(IrisLogger.Level.ERROR, parseIrisLogLevel("invalid"))
    }

    @Test
    fun `parses none log level`() {
        assertEquals(IrisLogger.Level.NONE, parseIrisLogLevel("NONE"))
        assertEquals(IrisLogger.Level.NONE, parseIrisLogLevel(" none "))
    }
}
