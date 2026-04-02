package party.qwer.iris.persistence

import java.io.Closeable

data class PendingWebhookDelivery(
    val messageId: String,
    val roomId: Long,
    val route: String,
    val payloadJson: String,
)

data class ClaimedDelivery(
    val id: Long,
    val messageId: String,
    val roomId: Long,
    val route: String,
    val payloadJson: String,
    /** 누적 실패 횟수. 성공 시 증가하지 않으므로 총 시도 횟수와 다르다. */
    val failedAttemptCount: Int,
    val claimToken: String,
)

/** CLAIMED 상태에서의 실패 처리 outcome. */
sealed interface FailureOutcome {
    /** 재시도 가능 실패. failedAttemptCount 증가. */
    data class Retry(
        val nextAttemptAt: Long,
        val reason: String?,
    ) : FailureOutcome

    /** HTTP 시도 후 영구 실패 (비재시도 상태코드, 재시도 소진). failedAttemptCount 증가. */
    data class PermanentFailure(
        val reason: String?,
    ) : FailureOutcome

    /** HTTP 시도 전 거부 (URL 미설정 등). failedAttemptCount 변경 없음. */
    data class RejectedBeforeAttempt(
        val reason: String?,
    ) : FailureOutcome
}

/**
 * CLAIMED 상태에서의 전이 결과.
 * [APPLIED]이면 전이 성공, [STALE_CLAIM]이면 claim_token 불일치 또는 이미 전이됨.
 */
enum class ClaimTransitionResult {
    APPLIED,
    STALE_CLAIM,
}

interface WebhookDeliveryStore : Closeable {
    fun enqueue(delivery: PendingWebhookDelivery): Long

    fun claimReady(limit: Int): List<ClaimedDelivery>

    /** 전달 성공. CLAIMED -> SENT. last_error를 정리함. */
    fun markSent(
        id: Long,
        claimToken: String,
    ): ClaimTransitionResult

    /**
     * 전달 실패 후 처리. outcome 타입에 따라 CLAIMED -> RETRY 또는 CLAIMED -> DEAD.
     * failedAttemptCount 증가 여부는 outcome 타입이 결정함.
     */
    fun resolveFailure(
        id: Long,
        claimToken: String,
        outcome: FailureOutcome,
    ): ClaimTransitionResult

    /**
     * 시도로 카운트하지 않는 CLAIMED -> RETRY 복귀.
     * 셧다운, 큐 포화 등 시스템 사유로 처리를 포기할 때 사용. failedAttemptCount 변경 없음.
     * requeueClaim은 HTTP 시도 전에만 사용해야 함.
     */
    fun requeueClaim(
        id: Long,
        claimToken: String,
        nextAttemptAt: Long,
        reason: String?,
    ): ClaimTransitionResult

    /**
     * 장시간 처리 중 CLAIMED lease를 연장한다.
     * recoverExpiredClaims가 in-flight 엔트리를 회수하지 않도록 heartbeat 용도로 사용한다.
     */
    fun renewClaim(
        id: Long,
        claimToken: String,
    ): ClaimTransitionResult

    fun recoverExpiredClaims(olderThanMs: Long): Int
}
