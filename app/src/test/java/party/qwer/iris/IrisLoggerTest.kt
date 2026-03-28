package party.qwer.iris

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IrisLoggerTest {
    @Test
    fun `parseIrisLogLevel defaults to error for unknown values`() {
        assertEquals(IrisLogger.Level.ERROR, parseIrisLogLevel(null))
        assertEquals(IrisLogger.Level.ERROR, parseIrisLogLevel(""))
        assertEquals(IrisLogger.Level.ERROR, parseIrisLogLevel("unknown"))
    }

    @Test
    fun `formatLogLine includes timestamp level and thread`() {
        val formatted =
            formatLogLine(
                level = "INFO",
                message = "component=test msg=hello",
                threadName = "worker-1",
                now = { Instant.parse("2026-03-27T00:00:00Z") },
            )

        assertTrue(formatted.contains("ts=2026-03-27T00:00:00Z"))
        assertTrue(formatted.contains("level=INFO"))
        assertTrue(formatted.contains("thread=worker-1"))
        assertTrue(formatted.endsWith("component=test msg=hello"))
    }
}
