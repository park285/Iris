package party.qwer.iris

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AndroidHiddenApiServiceLookupTest {
    @Test
    fun `requireLookupValue retries until value is available`() {
        var attempts = 0

        val value =
            requireLookupValue(
                label = "activity",
                attempts = 3,
                retryDelayMs = 0,
            ) {
                attempts += 1
                if (attempts == 3) {
                    "ready"
                } else {
                    null
                }
            }

        assertEquals("ready", value)
        assertEquals(3, attempts)
    }

    @Test
    fun `requireLookupValue fails with descriptive error after retries`() {
        val error =
            assertFailsWith<IllegalStateException> {
                requireLookupValue(
                    label = "activity",
                    attempts = 2,
                    retryDelayMs = 0,
                ) {
                    null
                }
            }

        assertEquals("activity unavailable after 2 attempts", error.message)
    }
}
