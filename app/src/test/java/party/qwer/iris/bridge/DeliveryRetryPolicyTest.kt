package party.qwer.iris.bridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

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

    @Test
    fun `returns retry eligibility for status boundary cases`() {
        val cases =
            listOf(
                200 to false,
                400 to false,
                407 to false,
                408 to true,
                429 to true,
                499 to false,
                500 to true,
                503 to true,
            )

        cases.forEach { (statusCode, expected) ->
            val actual = shouldRetryStatus(statusCode)

            if (expected) {
                assertTrue(actual, "statusCode=$statusCode")
            } else {
                assertFalse(actual, "statusCode=$statusCode")
            }
        }
    }

    @Test
    fun `returns retry attempt for first delivery attempt`() {
        val schedule =
            nextDeliveryRetrySchedule(
                attempt = 0,
                maxDeliveryAttempts = 6,
                backoffDelayProvider = { 1_234L },
            )

        assertEquals(
            DeliveryRetrySchedule.RetryAttempt(
                nextAttempt = 1,
                delayMs = 1_234L,
            ),
            schedule,
        )
    }

    @Test
    fun `returns exhausted when attempt is last allowed retry`() {
        val schedule =
            nextDeliveryRetrySchedule(
                attempt = 5,
                maxDeliveryAttempts = 6,
                backoffDelayProvider = { error("should not be called") },
            )

        assertIs<DeliveryRetrySchedule.Exhausted>(schedule)
    }

    @Test
    fun `returns exhausted when attempt exceeds max attempts`() {
        val schedule =
            nextDeliveryRetrySchedule(
                attempt = 6,
                maxDeliveryAttempts = 6,
                backoffDelayProvider = { error("should not be called") },
            )

        assertIs<DeliveryRetrySchedule.Exhausted>(schedule)
    }

    @Test
    fun `returns first backoff delay within base range`() {
        val delay = nextBackoffDelayMs(attempt = 0)

        assertTrue(delay in 1_000L..1_500L, "delay=$delay")
    }
}
