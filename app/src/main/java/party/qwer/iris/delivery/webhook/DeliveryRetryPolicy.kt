package party.qwer.iris.delivery.webhook

import kotlin.random.Random

private const val MAX_BACKOFF_EXPONENT = 5
private const val MAX_BACKOFF_DELAY_MS = 30_000L
private const val MAX_BACKOFF_JITTER_MS = 500L

internal sealed interface DeliveryRetrySchedule {
    data class RetryAttempt(
        val nextAttempt: Int,
        val delayMs: Long,
    ) : DeliveryRetrySchedule

    data object Exhausted : DeliveryRetrySchedule
}

internal fun nextDeliveryRetrySchedule(
    attempt: Int,
    maxDeliveryAttempts: Int = 6,
    backoffDelayProvider: (Int) -> Long = ::nextBackoffDelayMs,
): DeliveryRetrySchedule =
    if (attempt < maxDeliveryAttempts - 1) {
        DeliveryRetrySchedule.RetryAttempt(
            nextAttempt = attempt + 1,
            delayMs = backoffDelayProvider(attempt),
        )
    } else {
        DeliveryRetrySchedule.Exhausted
    }

internal fun nextBackoffDelayMs(attempt: Int): Long =
    nextBackoffDelayMs(attempt) {
        Random.nextLong(0, MAX_BACKOFF_JITTER_MS + 1)
    }

internal fun nextBackoffDelayMs(
    attempt: Int,
    jitterProvider: () -> Long,
): Long {
    val cappedAttempt = attempt.coerceAtMost(MAX_BACKOFF_EXPONENT)
    val baseDelay = 1_000L shl cappedAttempt
    val boundedDelay = baseDelay.coerceAtMost(MAX_BACKOFF_DELAY_MS)
    return boundedDelay + jitterProvider()
}

internal fun shouldRetryStatus(statusCode: Int): Boolean = statusCode == 408 || statusCode == 429 || statusCode >= 500
