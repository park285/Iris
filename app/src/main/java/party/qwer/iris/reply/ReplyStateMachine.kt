package party.qwer.iris.reply

import party.qwer.iris.model.ReplyLifecycleState
import party.qwer.iris.model.ReplyLifecycleState.FAILED
import party.qwer.iris.model.ReplyLifecycleState.HANDOFF_COMPLETED
import party.qwer.iris.model.ReplyLifecycleState.PREPARED
import party.qwer.iris.model.ReplyLifecycleState.PREPARING
import party.qwer.iris.model.ReplyLifecycleState.QUEUED
import party.qwer.iris.model.ReplyLifecycleState.SENDING

object ReplyStateMachine {
    fun next(
        current: ReplyLifecycleState,
        event: ReplyTransitionEvent,
    ): ReplyLifecycleState =
        when (current) {
            QUEUED ->
                when (event) {
                    ReplyTransitionEvent.PrepareStarted -> PREPARING
                    is ReplyTransitionEvent.Failed -> FAILED
                    ReplyTransitionEvent.PrepareCompleted,
                    ReplyTransitionEvent.SendStarted,
                    ReplyTransitionEvent.SendCompleted,
                    -> throw IllegalStateException("Invalid transition: $current + $event")
                }

            PREPARING ->
                when (event) {
                    ReplyTransitionEvent.PrepareCompleted -> PREPARED
                    is ReplyTransitionEvent.Failed -> FAILED
                    ReplyTransitionEvent.PrepareStarted,
                    ReplyTransitionEvent.SendStarted,
                    ReplyTransitionEvent.SendCompleted,
                    -> throw IllegalStateException("Invalid transition: $current + $event")
                }

            PREPARED ->
                when (event) {
                    ReplyTransitionEvent.SendStarted -> SENDING
                    is ReplyTransitionEvent.Failed -> FAILED
                    ReplyTransitionEvent.PrepareStarted,
                    ReplyTransitionEvent.PrepareCompleted,
                    ReplyTransitionEvent.SendCompleted,
                    -> throw IllegalStateException("Invalid transition: $current + $event")
                }

            SENDING ->
                when (event) {
                    ReplyTransitionEvent.SendCompleted -> HANDOFF_COMPLETED
                    is ReplyTransitionEvent.Failed -> FAILED
                    ReplyTransitionEvent.PrepareStarted,
                    ReplyTransitionEvent.PrepareCompleted,
                    ReplyTransitionEvent.SendStarted,
                    -> throw IllegalStateException("Invalid transition: $current + $event")
                }

            HANDOFF_COMPLETED, FAILED -> throw IllegalStateException("Terminal state: $current")
        }
}
