package party.qwer.iris

import kotlin.test.Test
import kotlin.test.assertEquals

class MemberNicknameDecisionEngineTest {
    @Test
    fun `high confidence live rename confirms immediately`() {
        val engine = MemberNicknameDecisionEngine()
        val state = MemberNicknameRuntimeState(confirmedNickname = "Alice")
        state.recentConfirmedHistory.addLast(ConfirmedNicknameEntry("Alice", 1L))

        val decision =
            engine.apply(
                state,
                NicknameObservation(
                    nickname = "Alice Updated",
                    source = NicknameEvidenceSource.LIVE,
                    confidence = NicknameEvidenceConfidence.HIGH,
                    observedAtMs = 2L,
                ),
            )

        assertEquals(NicknameDecision.Confirm("Alice", "Alice Updated"), decision)
    }

    @Test
    fun `low confidence live rename does not confirm by repetition only`() {
        val engine = MemberNicknameDecisionEngine()
        val state = MemberNicknameRuntimeState(confirmedNickname = "Alice")
        state.recentConfirmedHistory.addLast(ConfirmedNicknameEntry("Alice", 1L))

        repeat(3) {
            val decision =
                engine.apply(
                    state,
                    NicknameObservation(
                        nickname = "Notice",
                        source = NicknameEvidenceSource.LIVE,
                        confidence = NicknameEvidenceConfidence.LOW,
                        observedAtMs = (it + 2).toLong(),
                    ),
                )
            assertEquals(NicknameDecision.NoChange, decision)
        }
    }

    @Test
    fun `low confidence live rename confirms when db corroborates same nickname`() {
        val engine = MemberNicknameDecisionEngine()
        val state = MemberNicknameRuntimeState(confirmedNickname = "Alice")
        state.recentConfirmedHistory.addLast(ConfirmedNicknameEntry("Alice", 1L))

        engine.apply(
            state,
            NicknameObservation(
                nickname = "Alice Updated",
                source = NicknameEvidenceSource.LIVE,
                confidence = NicknameEvidenceConfidence.LOW,
                observedAtMs = 2L,
            ),
        )

        val decision =
            engine.apply(
                state,
                NicknameObservation(
                    nickname = "Alice Updated",
                    source = NicknameEvidenceSource.DB,
                    confidence = NicknameEvidenceConfidence.MEDIUM,
                    observedAtMs = 3L,
                ),
            )

        assertEquals(NicknameDecision.Confirm("Alice", "Alice Updated"), decision)
    }

    @Test
    fun `reverse rename requires two confirmations`() {
        val engine = MemberNicknameDecisionEngine()
        val state = MemberNicknameRuntimeState(confirmedNickname = "Bobby")
        state.recentConfirmedHistory.addLast(ConfirmedNicknameEntry("Alice", 1L))
        state.recentConfirmedHistory.addLast(ConfirmedNicknameEntry("Bobby", 2L))

        val first =
            engine.apply(
                state,
                NicknameObservation(
                    nickname = "Alice",
                    source = NicknameEvidenceSource.LIVE,
                    confidence = NicknameEvidenceConfidence.HIGH,
                    observedAtMs = 3L,
                ),
            )
        assertEquals(NicknameDecision.NoChange, first)

        val second =
            engine.apply(
                state,
                NicknameObservation(
                    nickname = "Alice",
                    source = NicknameEvidenceSource.LIVE,
                    confidence = NicknameEvidenceConfidence.HIGH,
                    observedAtMs = 4L,
                ),
            )
        assertEquals(NicknameDecision.Confirm("Bobby", "Alice"), second)
    }

    @Test
    fun `db non-reverse rename confirms on first observation`() {
        val engine = MemberNicknameDecisionEngine()
        val state = MemberNicknameRuntimeState(confirmedNickname = "Alice")
        state.recentConfirmedHistory.addLast(ConfirmedNicknameEntry("Alice", 1L))

        val decision =
            engine.apply(
                state,
                NicknameObservation(
                    nickname = "Alice Updated",
                    source = NicknameEvidenceSource.DB,
                    confidence = NicknameEvidenceConfidence.MEDIUM,
                    observedAtMs = 2L,
                ),
            )
        assertEquals(NicknameDecision.Confirm("Alice", "Alice Updated"), decision)
    }

    @Test
    fun `db reverse rename requires two observations`() {
        val engine = MemberNicknameDecisionEngine()
        val state = MemberNicknameRuntimeState(confirmedNickname = "Bobby")
        state.recentConfirmedHistory.addLast(ConfirmedNicknameEntry("Alice", 1L))
        state.recentConfirmedHistory.addLast(ConfirmedNicknameEntry("Bobby", 2L))

        val first =
            engine.apply(
                state,
                NicknameObservation(
                    nickname = "Alice",
                    source = NicknameEvidenceSource.DB,
                    confidence = NicknameEvidenceConfidence.MEDIUM,
                    observedAtMs = 3L,
                ),
            )
        assertEquals(NicknameDecision.NoChange, first)

        val second =
            engine.apply(
                state,
                NicknameObservation(
                    nickname = "Alice",
                    source = NicknameEvidenceSource.DB,
                    confidence = NicknameEvidenceConfidence.MEDIUM,
                    observedAtMs = 4L,
                ),
            )
        assertEquals(NicknameDecision.Confirm("Bobby", "Alice"), second)
    }

    @Test
    fun `low confidence live rename confirms after threshold observations`() {
        val engine = MemberNicknameDecisionEngine()
        val state = MemberNicknameRuntimeState(confirmedNickname = "Alice")
        state.recentConfirmedHistory.addLast(ConfirmedNicknameEntry("Alice", 1L))

        repeat(MemberNicknameDecisionEngine.LOW_CONFIDENCE_THRESHOLD - 1) { i ->
            val decision =
                engine.apply(
                    state,
                    NicknameObservation(
                        nickname = "Alice Updated",
                        source = NicknameEvidenceSource.LIVE,
                        confidence = NicknameEvidenceConfidence.LOW,
                        observedAtMs = (i + 2).toLong(),
                    ),
                )
            assertEquals(NicknameDecision.NoChange, decision, "iteration $i should not confirm")
        }

        val confirming =
            engine.apply(
                state,
                NicknameObservation(
                    nickname = "Alice Updated",
                    source = NicknameEvidenceSource.LIVE,
                    confidence = NicknameEvidenceConfidence.LOW,
                    observedAtMs = 100L,
                ),
            )
        assertEquals(NicknameDecision.Confirm("Alice", "Alice Updated"), confirming)
    }
}
