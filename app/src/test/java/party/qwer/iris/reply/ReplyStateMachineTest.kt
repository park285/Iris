package party.qwer.iris.reply

import party.qwer.iris.model.ReplyLifecycleState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ReplyStateMachineTest {
    data class ValidTransition(
        val from: ReplyLifecycleState,
        val event: ReplyTransitionEvent,
        val expected: ReplyLifecycleState,
    )

    private val validTransitions =
        listOf(
            ValidTransition(ReplyLifecycleState.QUEUED, ReplyTransitionEvent.PrepareStarted, ReplyLifecycleState.PREPARING),
            ValidTransition(ReplyLifecycleState.QUEUED, ReplyTransitionEvent.Failed("err"), ReplyLifecycleState.FAILED),
            ValidTransition(ReplyLifecycleState.PREPARING, ReplyTransitionEvent.PrepareCompleted, ReplyLifecycleState.PREPARED),
            ValidTransition(ReplyLifecycleState.PREPARING, ReplyTransitionEvent.Failed("err"), ReplyLifecycleState.FAILED),
            ValidTransition(ReplyLifecycleState.PREPARED, ReplyTransitionEvent.SendStarted, ReplyLifecycleState.SENDING),
            ValidTransition(ReplyLifecycleState.PREPARED, ReplyTransitionEvent.Failed("err"), ReplyLifecycleState.FAILED),
            ValidTransition(ReplyLifecycleState.SENDING, ReplyTransitionEvent.SendCompleted, ReplyLifecycleState.HANDOFF_COMPLETED),
            ValidTransition(ReplyLifecycleState.SENDING, ReplyTransitionEvent.Failed("err"), ReplyLifecycleState.FAILED),
        )

    @Test
    fun `all valid transitions produce expected state`() {
        validTransitions.forEach { (from, event, expected) ->
            val result = ReplyStateMachine.next(from, event)
            assertEquals(expected, result, "Transition $from + $event should produce $expected")
        }
    }

    data class InvalidTransition(
        val from: ReplyLifecycleState,
        val event: ReplyTransitionEvent,
    )

    private val invalidTransitions =
        listOf(
            InvalidTransition(ReplyLifecycleState.QUEUED, ReplyTransitionEvent.PrepareCompleted),
            InvalidTransition(ReplyLifecycleState.QUEUED, ReplyTransitionEvent.SendStarted),
            InvalidTransition(ReplyLifecycleState.QUEUED, ReplyTransitionEvent.SendCompleted),
            InvalidTransition(ReplyLifecycleState.PREPARING, ReplyTransitionEvent.PrepareStarted),
            InvalidTransition(ReplyLifecycleState.PREPARING, ReplyTransitionEvent.SendStarted),
            InvalidTransition(ReplyLifecycleState.PREPARING, ReplyTransitionEvent.SendCompleted),
            InvalidTransition(ReplyLifecycleState.PREPARED, ReplyTransitionEvent.PrepareStarted),
            InvalidTransition(ReplyLifecycleState.PREPARED, ReplyTransitionEvent.PrepareCompleted),
            InvalidTransition(ReplyLifecycleState.PREPARED, ReplyTransitionEvent.SendCompleted),
            InvalidTransition(ReplyLifecycleState.SENDING, ReplyTransitionEvent.PrepareStarted),
            InvalidTransition(ReplyLifecycleState.SENDING, ReplyTransitionEvent.PrepareCompleted),
            InvalidTransition(ReplyLifecycleState.SENDING, ReplyTransitionEvent.SendStarted),
        )

    @Test
    fun `invalid transitions throw IllegalStateException`() {
        invalidTransitions.forEach { (from, event) ->
            assertFailsWith<IllegalStateException> {
                ReplyStateMachine.next(from, event)
            }
        }
    }

    @Test
    fun `terminal state HANDOFF_COMPLETED rejects all events`() {
        val events =
            listOf(
                ReplyTransitionEvent.PrepareStarted,
                ReplyTransitionEvent.PrepareCompleted,
                ReplyTransitionEvent.SendStarted,
                ReplyTransitionEvent.SendCompleted,
                ReplyTransitionEvent.Failed("err"),
            )

        events.forEach { event ->
            val error =
                assertFailsWith<IllegalStateException> {
                    ReplyStateMachine.next(ReplyLifecycleState.HANDOFF_COMPLETED, event)
                }
            assertEquals("Terminal state: HANDOFF_COMPLETED", error.message)
        }
    }

    @Test
    fun `terminal state FAILED rejects all events`() {
        val events =
            listOf(
                ReplyTransitionEvent.PrepareStarted,
                ReplyTransitionEvent.PrepareCompleted,
                ReplyTransitionEvent.SendStarted,
                ReplyTransitionEvent.SendCompleted,
                ReplyTransitionEvent.Failed("err"),
            )

        events.forEach { event ->
            val error =
                assertFailsWith<IllegalStateException> {
                    ReplyStateMachine.next(ReplyLifecycleState.FAILED, event)
                }
            assertEquals("Terminal state: FAILED", error.message)
        }
    }

    @Test
    fun `Failed event preserves reason string`() {
        val event = ReplyTransitionEvent.Failed("decode error: invalid PNG header")
        val result = ReplyStateMachine.next(ReplyLifecycleState.QUEUED, event)
        assertEquals(ReplyLifecycleState.FAILED, result)
        assertEquals("decode error: invalid PNG header", event.reason)
    }

    @Test
    fun `happy path traverses full lifecycle`() {
        var state = ReplyLifecycleState.QUEUED
        state = ReplyStateMachine.next(state, ReplyTransitionEvent.PrepareStarted)
        assertEquals(ReplyLifecycleState.PREPARING, state)
        state = ReplyStateMachine.next(state, ReplyTransitionEvent.PrepareCompleted)
        assertEquals(ReplyLifecycleState.PREPARED, state)
        state = ReplyStateMachine.next(state, ReplyTransitionEvent.SendStarted)
        assertEquals(ReplyLifecycleState.SENDING, state)
        state = ReplyStateMachine.next(state, ReplyTransitionEvent.SendCompleted)
        assertEquals(ReplyLifecycleState.HANDOFF_COMPLETED, state)
    }
}
