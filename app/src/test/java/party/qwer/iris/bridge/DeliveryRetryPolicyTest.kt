package party.qwer.iris.bridge

import kotlin.test.Test
import kotlin.test.assertEquals

class DeliveryRetryPolicyTest {
    @Test
    fun `caps exponential backoff before applying jitter`() {
        val delay =
            nextBackoffDelayMs(
                attempt = 9,
                jitterProvider = { 17L },
            )

        assertEquals(30_017L, delay)
    }
}
