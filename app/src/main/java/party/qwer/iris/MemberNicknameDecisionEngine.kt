@file:Suppress("ProguardSerializableOutsideModel")

package party.qwer.iris

import kotlinx.serialization.Serializable

@Serializable
internal enum class NicknameEvidenceSource {
    LIVE,
    DB,
}

@Serializable
internal enum class NicknameEvidenceConfidence {
    HIGH,
    MEDIUM,
    LOW,
}

internal data class NicknameObservation(
    val nickname: String,
    val source: NicknameEvidenceSource,
    val confidence: NicknameEvidenceConfidence,
    val observedAtMs: Long,
    val planFingerprint: String? = null,
)

internal data class ConfirmedNicknameEntry(
    val nickname: String,
    val confirmedAtMs: Long,
)

internal data class PendingNicknameObservation(
    val nickname: String,
    val source: NicknameEvidenceSource,
    val confidence: NicknameEvidenceConfidence,
    val planFingerprint: String?,
    val firstSeenAtMs: Long,
    val lastSeenAtMs: Long,
    val sameEvidenceCount: Int,
    val corroboratedByOtherSource: Boolean,
)

internal data class MemberNicknameRuntimeState(
    var confirmedNickname: String? = null,
    val recentConfirmedHistory: ArrayDeque<ConfirmedNicknameEntry> = ArrayDeque(),
    var pending: PendingNicknameObservation? = null,
)

internal sealed interface NicknameDecision {
    data object NoChange : NicknameDecision

    data class Seed(
        val nickname: String,
    ) : NicknameDecision

    data class Confirm(
        val oldNickname: String,
        val newNickname: String,
    ) : NicknameDecision
}

internal class MemberNicknameDecisionEngine(
    private val historyLimit: Int = 3,
) {
    companion object {
        const val LOW_CONFIDENCE_THRESHOLD = 5
        const val LOW_CONFIDENCE_REVERSE_THRESHOLD = 8
    }

    fun apply(
        state: MemberNicknameRuntimeState,
        observation: NicknameObservation?,
    ): NicknameDecision {
        val confirmed = state.confirmedNickname
        if (observation == null) {
            return NicknameDecision.NoChange
        }

        if (confirmed == null) {
            state.confirmedNickname = observation.nickname
            rememberConfirmed(state, observation.nickname, observation.observedAtMs)
            state.pending = null
            return NicknameDecision.Seed(observation.nickname)
        }

        if (confirmed == observation.nickname) {
            state.pending = null
            return NicknameDecision.NoChange
        }

        val reverseCandidate =
            state.recentConfirmedHistory
                .dropLast(1)
                .any { entry -> entry.nickname == observation.nickname }

        val pending = updatePending(state.pending, observation)
        state.pending = pending

        val requiredConfirmations = requiredConfirmations(observation, reverseCandidate)
        val confirmedByRule =
            pending.corroboratedByOtherSource ||
                pending.sameEvidenceCount >= requiredConfirmations

        if (!confirmedByRule) {
            return NicknameDecision.NoChange
        }

        val oldNickname = confirmed
        state.confirmedNickname = observation.nickname
        rememberConfirmed(state, observation.nickname, observation.observedAtMs)
        state.pending = null
        return NicknameDecision.Confirm(oldNickname = oldNickname, newNickname = observation.nickname)
    }

    private fun updatePending(
        current: PendingNicknameObservation?,
        observation: NicknameObservation,
    ): PendingNicknameObservation {
        if (current == null || current.nickname != observation.nickname) {
            return PendingNicknameObservation(
                nickname = observation.nickname,
                source = observation.source,
                confidence = observation.confidence,
                planFingerprint = observation.planFingerprint,
                firstSeenAtMs = observation.observedAtMs,
                lastSeenAtMs = observation.observedAtMs,
                sameEvidenceCount = 1,
                corroboratedByOtherSource = false,
            )
        }

        val corroborated =
            current.corroboratedByOtherSource ||
                current.source != observation.source ||
                confidenceRank(observation.confidence) > confidenceRank(current.confidence)

        return current.copy(
            source = if (confidenceRank(observation.confidence) >= confidenceRank(current.confidence)) observation.source else current.source,
            confidence = maxConfidence(current.confidence, observation.confidence),
            planFingerprint = observation.planFingerprint ?: current.planFingerprint,
            lastSeenAtMs = observation.observedAtMs,
            sameEvidenceCount = current.sameEvidenceCount + 1,
            corroboratedByOtherSource = corroborated,
        )
    }

    private fun requiredConfirmations(
        observation: NicknameObservation,
        reverseCandidate: Boolean,
    ): Int =
        when (observation.source) {
            NicknameEvidenceSource.LIVE ->
                when (observation.confidence) {
                    NicknameEvidenceConfidence.HIGH -> if (reverseCandidate) 2 else 1
                    NicknameEvidenceConfidence.MEDIUM -> 2
                    // LOW confidence라도 반복 관측 시 confirm 허용 (이전: Int.MAX_VALUE → 영구 차단)
                    NicknameEvidenceConfidence.LOW -> if (reverseCandidate) LOW_CONFIDENCE_REVERSE_THRESHOLD else LOW_CONFIDENCE_THRESHOLD
                }

            NicknameEvidenceSource.DB -> if (reverseCandidate) 2 else 1
        }

    private fun rememberConfirmed(
        state: MemberNicknameRuntimeState,
        nickname: String,
        confirmedAtMs: Long,
    ) {
        if (state.recentConfirmedHistory.lastOrNull()?.nickname == nickname) {
            return
        }
        state.recentConfirmedHistory.addLast(ConfirmedNicknameEntry(nickname, confirmedAtMs))
        while (state.recentConfirmedHistory.size > historyLimit) {
            state.recentConfirmedHistory.removeFirst()
        }
    }

    private fun confidenceRank(confidence: NicknameEvidenceConfidence): Int =
        when (confidence) {
            NicknameEvidenceConfidence.HIGH -> 3
            NicknameEvidenceConfidence.MEDIUM -> 2
            NicknameEvidenceConfidence.LOW -> 1
        }

    private fun maxConfidence(
        left: NicknameEvidenceConfidence,
        right: NicknameEvidenceConfidence,
    ): NicknameEvidenceConfidence = if (confidenceRank(left) >= confidenceRank(right)) left else right
}
