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
            val claimToken = UUID.randomUUID().toString()
            val readyIds =
                query(
                    """SELECT id FROM ${IrisDatabaseSchema.WEBHOOK_OUTBOX_TABLE}
                       WHERE status IN ('PENDING', 'RETRY') AND next_attempt_at <= ?
                       ORDER BY id LIMIT ?""",
                    listOf(now, limit),
                ) { row -> row.getLong(0) }
            if (readyIds.isEmpty()) {
                return@inImmediateTransaction emptyList()
            }

            val placeholders = readyIds.joinToString(",") { "?" }
            update(
                """UPDATE ${IrisDatabaseSchema.WEBHOOK_OUTBOX_TABLE}
                   SET status = 'CLAIMED', claim_token = ?, claimed_at = ?, updated_at = ?
                   WHERE id IN ($placeholders)""",
                listOf(claimToken, now, now) + readyIds,
            )

            query(
                """SELECT id, message_id, room_id, route, payload_json, attempt_count
                   FROM ${IrisDatabaseSchema.WEBHOOK_OUTBOX_TABLE}
                   WHERE id IN ($placeholders) ORDER BY id""",
                readyIds,
            ) { row ->
                ClaimedDelivery(
                    id = row.getLong(0),
                    messageId = row.getString(1),
                    roomId = row.getLong(2),
                    route = row.getString(3),
                    payloadJson = row.getString(4),
                    attemptCount = row.getInt(5),
                    claimToken = claimToken,
                )
            }
        }

    override fun markSent(
        id: Long,
        claimToken: String,
    ) {
        db.update(
            """UPDATE ${IrisDatabaseSchema.WEBHOOK_OUTBOX_TABLE}
               SET status = 'SENT', claim_token = NULL, claimed_at = NULL, updated_at = ?
               WHERE id = ? AND claim_token = ?""",
            listOf(clock(), id, claimToken),
        )
    }

    override fun markRetry(
        id: Long,
        claimToken: String,
        nextAttemptAt: Long,
        reason: String?,
    ) {
        db.update(
            """UPDATE ${IrisDatabaseSchema.WEBHOOK_OUTBOX_TABLE}
               SET status = 'RETRY', attempt_count = attempt_count + 1,
                   next_attempt_at = ?, last_error = ?, claim_token = NULL, claimed_at = NULL, updated_at = ?
               WHERE id = ? AND claim_token = ?""",
            listOf(nextAttemptAt, reason, clock(), id, claimToken),
        )
    }

    override fun markDead(
        id: Long,
        claimToken: String,
        reason: String?,
    ) {
        db.update(
            """UPDATE ${IrisDatabaseSchema.WEBHOOK_OUTBOX_TABLE}
               SET status = 'DEAD', last_error = ?, claim_token = NULL, claimed_at = NULL, updated_at = ?
               WHERE id = ? AND claim_token = ?""",
            listOf(reason, clock(), id, claimToken),
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
}
