package party.qwer.iris.delivery.webhook

import party.qwer.iris.persistence.ClaimTransitionResult
import party.qwer.iris.persistence.ClaimedDelivery

internal fun interface ClaimTransitionObserver {
    fun onResult(
        operation: String,
        entry: ClaimedDelivery,
        result: ClaimTransitionResult,
    )
}
