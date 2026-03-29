package party.qwer.iris.reply

import party.qwer.iris.IrisLogger
import party.qwer.iris.ReplyStatusStore
import party.qwer.iris.model.ReplyLifecycleState
import party.qwer.iris.model.ReplyStatusSnapshot
import java.util.concurrent.atomic.AtomicLong

internal class ReplyStatusTracker(
    private val store: ReplyStatusStore,
    private val failFastOnInvalidTransition: Boolean = false,
) {
    private val invalidTransitionCount = AtomicLong(0)

    fun onQueued(requestId: String?) {
        if (requestId == null) {
            return
        }
        store.update(requestId, ReplyLifecycleState.QUEUED)
    }

    fun transition(
        requestId: String?,
        event: ReplyTransitionEvent,
    ) {
        if (requestId == null) {
            return
        }
        val current = store.get(requestId) ?: return
        val nextState =
            try {
                ReplyStateMachine.next(current.state, event)
            } catch (e: IllegalStateException) {
                invalidTransitionCount.incrementAndGet()
                IrisLogger.error("[ReplyStatusTracker] Invalid transition for $requestId: ${e.message}")
                if (failFastOnInvalidTransition) {
                    throw e
                }
                return
            }
        val detail = if (event is ReplyTransitionEvent.Failed) event.reason else null
        store.update(requestId, nextState, detail)
    }

    fun get(requestId: String): ReplyStatusSnapshot? = store.get(requestId)

    fun invalidTransitionCount(): Long = invalidTransitionCount.get()
}
