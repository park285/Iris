package party.qwer.iris.persistence

import java.util.UUID

class SqliteWebhookDeliveryStore(
    private val db: SqliteDriver,
    private val clock: () -> Long = System::currentTimeMillis,
) : WebhookDeliveryStore {
    override fun enqueue(delivery: PendingWebhookDelivery): Long =
        db.inImmediateTransaction {
            val now = clock()
            update(
                """INSERT OR IGNORE INTO ${IrisDatabaseSchema.WEBHOOK_OUTBOX_TABLE}
                   (message_id, room_id, route, payload_json, status, attempt_count, next_attempt_at, created_at, updated_at)
                   VALUES (?, ?, ?, ?, 'PENDING', 0, 0, ?, ?)""",
                listOf(delivery.messageId, delivery.roomId, delivery.route, delivery.payloadJson, now, now),
            )
            queryLong(
                "SELECT id FROM ${IrisDatabaseSchema.WEBHOOK_OUTBOX_TABLE} WHERE message_id = ?",
                delivery.messageId,
            )!!
        }

    override fun claimReady(limit: Int): List<ClaimedDelivery> =
        db.inImmediateTransaction {
            val now = clock()
            val rows =
                query(
                    """SELECT id, message_id, room_id, route, payload_json, attempt_count
                       FROM ${IrisDatabaseSchema.WEBHOOK_OUTBOX_TABLE}
                       WHERE status IN ('PENDING', 'RETRY') AND next_attempt_at <= ?
                       ORDER BY id LIMIT ?""",
                    listOf(now, limit),
                ) { row ->
                    val id = row.getLong(0)
                    val claimToken = UUID.randomUUID().toString()
                    update(
                        """UPDATE ${IrisDatabaseSchema.WEBHOOK_OUTBOX_TABLE}
                           SET status = 'CLAIMED', claim_token = ?, claimed_at = ?, updated_at = ?
                           WHERE id = ? AND status IN ('PENDING', 'RETRY')""",
                        listOf(claimToken, now, now, id),
                    )
                    ClaimedDelivery(
                        id = id,
                        messageId = row.getString(1),
                        roomId = row.getLong(2),
                        route = row.getString(3),
                        payloadJson = row.getString(4),
                        failedAttemptCount = row.getInt(5),
                        claimToken = claimToken,
                    )
                }
            rows
        }

    override fun markSent(
        id: Long,
        claimToken: String,
    ): ClaimTransitionResult =
        transitionResult(
            db.update(
                """UPDATE ${IrisDatabaseSchema.WEBHOOK_OUTBOX_TABLE}
                   SET status = 'SENT', last_error = NULL,
                       claim_token = NULL, claimed_at = NULL, updated_at = ?
                   WHERE id = ? AND claim_token = ? AND status = 'CLAIMED'""",
                listOf(clock(), id, claimToken),
            ),
        )

    override fun resolveFailure(
        id: Long,
        claimToken: String,
        outcome: FailureOutcome,
    ): ClaimTransitionResult =
        transitionResult(
            when (outcome) {
                is FailureOutcome.Retry ->
                    db.update(
                        """UPDATE ${IrisDatabaseSchema.WEBHOOK_OUTBOX_TABLE}
                           SET status = 'RETRY', attempt_count = attempt_count + 1,
                               next_attempt_at = ?, last_error = ?,
                               claim_token = NULL, claimed_at = NULL, updated_at = ?
                           WHERE id = ? AND claim_token = ? AND status = 'CLAIMED'""",
                        listOf(outcome.nextAttemptAt, outcome.reason, clock(), id, claimToken),
                    )

                is FailureOutcome.PermanentFailure ->
                    db.update(
                        """UPDATE ${IrisDatabaseSchema.WEBHOOK_OUTBOX_TABLE}
                           SET status = 'DEAD', attempt_count = attempt_count + 1,
                               last_error = ?,
                               claim_token = NULL, claimed_at = NULL, updated_at = ?
                           WHERE id = ? AND claim_token = ? AND status = 'CLAIMED'""",
                        listOf(outcome.reason, clock(), id, claimToken),
                    )

                is FailureOutcome.RejectedBeforeAttempt ->
                    db.update(
                        """UPDATE ${IrisDatabaseSchema.WEBHOOK_OUTBOX_TABLE}
                           SET status = 'DEAD',
                               last_error = ?,
                               claim_token = NULL, claimed_at = NULL, updated_at = ?
                           WHERE id = ? AND claim_token = ? AND status = 'CLAIMED'""",
                        listOf(outcome.reason, clock(), id, claimToken),
                    )
            },
        )

    override fun requeueClaim(
        id: Long,
        claimToken: String,
        nextAttemptAt: Long,
        reason: String?,
    ): ClaimTransitionResult =
        transitionResult(
            db.update(
                """UPDATE ${IrisDatabaseSchema.WEBHOOK_OUTBOX_TABLE}
                   SET status = 'RETRY',
                       next_attempt_at = ?, last_error = ?,
                       claim_token = NULL, claimed_at = NULL, updated_at = ?
                   WHERE id = ? AND claim_token = ? AND status = 'CLAIMED'""",
                listOf(nextAttemptAt, reason, clock(), id, claimToken),
            ),
        )

    override fun renewClaim(
        id: Long,
        claimToken: String,
    ): ClaimTransitionResult {
        val now = clock()
        return transitionResult(
            db.update(
                """UPDATE ${IrisDatabaseSchema.WEBHOOK_OUTBOX_TABLE}
                   SET claimed_at = ?, updated_at = ?
                   WHERE id = ? AND claim_token = ? AND status = 'CLAIMED'""",
                listOf(now, now, id, claimToken),
            ),
        )
    }

    override fun recoverExpiredClaims(olderThanMs: Long): Int {
        val cutoff = clock() - olderThanMs
        return db.update(
            """UPDATE ${IrisDatabaseSchema.WEBHOOK_OUTBOX_TABLE}
               SET status = 'RETRY', claim_token = NULL, claimed_at = NULL, updated_at = ?
               WHERE status = 'CLAIMED' AND claimed_at <= ?""",
            listOf(clock(), cutoff),
        )
    }

    override fun close() {}

    private fun transitionResult(affectedRows: Int): ClaimTransitionResult =
        if (affectedRows > 0) ClaimTransitionResult.APPLIED else ClaimTransitionResult.STALE_CLAIM
}
