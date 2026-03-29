package party.qwer.iris.reply

import party.qwer.iris.ReplyStatusStore
import party.qwer.iris.model.ReplyLifecycleState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReplyStatusTrackerTest {
    @Test
    fun `tracks happy path lifecycle through state machine`() {
        val store = ReplyStatusStore()
        val tracker = ReplyStatusTracker(store)

        tracker.onQueued("req-1")
        assertEquals(ReplyLifecycleState.QUEUED, tracker.get("req-1")?.state)

        tracker.transition("req-1", ReplyTransitionEvent.PrepareStarted)
        assertEquals(ReplyLifecycleState.PREPARING, tracker.get("req-1")?.state)

        tracker.transition("req-1", ReplyTransitionEvent.PrepareCompleted)
        assertEquals(ReplyLifecycleState.PREPARED, tracker.get("req-1")?.state)

        tracker.transition("req-1", ReplyTransitionEvent.SendStarted)
        assertEquals(ReplyLifecycleState.SENDING, tracker.get("req-1")?.state)

        tracker.transition("req-1", ReplyTransitionEvent.SendCompleted)
        assertEquals(ReplyLifecycleState.HANDOFF_COMPLETED, tracker.get("req-1")?.state)
    }

    @Test
    fun `tracks failure from any non-terminal state`() {
        val store = ReplyStatusStore()
        val tracker = ReplyStatusTracker(store)

        tracker.onQueued("req-fail")
        tracker.transition("req-fail", ReplyTransitionEvent.PrepareStarted)
        tracker.transition("req-fail", ReplyTransitionEvent.Failed("decode error"))

        val snapshot = tracker.get("req-fail")
        assertEquals(ReplyLifecycleState.FAILED, snapshot?.state)
        assertEquals("decode error", snapshot?.detail)
    }

    @Test
    fun `ignores transition for unknown request id`() {
        val store = ReplyStatusStore()
        val tracker = ReplyStatusTracker(store)

        tracker.transition("unknown", ReplyTransitionEvent.PrepareStarted)
        assertNull(tracker.get("unknown"))
    }

    @Test
    fun `ignores null request id`() {
        val store = ReplyStatusStore()
        val tracker = ReplyStatusTracker(store)

        tracker.onQueued(null)
        tracker.transition(null, ReplyTransitionEvent.PrepareStarted)
    }

    @Test
    fun `terminal state transition increments invalid transition count and keeps terminal state`() {
        val store = ReplyStatusStore()
        val tracker = ReplyStatusTracker(store)

        tracker.onQueued("req-terminal")
        tracker.transition("req-terminal", ReplyTransitionEvent.PrepareStarted)
        tracker.transition("req-terminal", ReplyTransitionEvent.PrepareCompleted)
        tracker.transition("req-terminal", ReplyTransitionEvent.SendStarted)
        tracker.transition("req-terminal", ReplyTransitionEvent.SendCompleted)

        tracker.transition("req-terminal", ReplyTransitionEvent.PrepareStarted)
        assertEquals(ReplyLifecycleState.HANDOFF_COMPLETED, tracker.get("req-terminal")?.state)
        assertEquals(1, tracker.invalidTransitionCount())
    }

    @Test
    fun `get returns null for untracked request`() {
        val store = ReplyStatusStore()
        val tracker = ReplyStatusTracker(store)

        assertNull(tracker.get("nonexistent"))
    }

    @Test
    fun `failed reason is preserved in status detail`() {
        val store = ReplyStatusStore()
        val tracker = ReplyStatusTracker(store)

        tracker.onQueued("req-reason")
        tracker.transition("req-reason", ReplyTransitionEvent.Failed("image too large"))

        val snapshot = tracker.get("req-reason")
        assertEquals(ReplyLifecycleState.FAILED, snapshot?.state)
        assertEquals("image too large", snapshot?.detail)
    }
}
