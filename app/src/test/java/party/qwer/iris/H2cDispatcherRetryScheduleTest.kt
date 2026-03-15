package party.qwer.iris

import party.qwer.iris.bridge.DeliveryRetrySchedule
import party.qwer.iris.bridge.nextDeliveryRetrySchedule
import kotlin.test.Test
import kotlin.test.assertEquals

class H2cDispatcherRetryScheduleTest {
    @Test
    fun `uses backoff and increments attempt before max attempts`() {
        val schedule =
            nextDeliveryRetrySchedule(
                attempt = 2,
                maxDeliveryAttempts = 6,
                backoffDelayProvider = { 1_500L + it },
            )

        assertEquals(
            DeliveryRetrySchedule.RetryAttempt(
                nextAttempt = 3,
                delayMs = 1_502L,
            ),
            schedule,
        )
    }

    @Test
    fun `marks delivery exhausted after the last attempt in a pass`() {
        val schedule =
            nextDeliveryRetrySchedule(
                attempt = 5,
                maxDeliveryAttempts = 6,
                backoffDelayProvider = { error("backoff should not be used after the last attempt") },
            )

        assertEquals(
            DeliveryRetrySchedule.Exhausted,
            schedule,
        )
    }
}
