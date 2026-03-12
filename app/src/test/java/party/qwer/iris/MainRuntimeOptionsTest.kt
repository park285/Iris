package party.qwer.iris

import kotlin.test.Test
import kotlin.test.assertEquals

class MainRuntimeOptionsTest {
    @Test
    fun `uses default duration when raw value is missing or invalid`() {
        assertEquals(1000L, positiveDurationMillisOrDefault(null, 1000L))
        assertEquals(1000L, positiveDurationMillisOrDefault("", 1000L))
        assertEquals(1000L, positiveDurationMillisOrDefault("0", 1000L))
        assertEquals(1000L, positiveDurationMillisOrDefault("-1", 1000L))
        assertEquals(1000L, positiveDurationMillisOrDefault("abc", 1000L))
    }

    @Test
    fun `uses parsed positive duration when valid`() {
        assertEquals(2500L, positiveDurationMillisOrDefault("2500", 1000L))
        assertEquals(2500L, positiveDurationMillisOrDefault(" 2500 ", 1000L))
    }
}
