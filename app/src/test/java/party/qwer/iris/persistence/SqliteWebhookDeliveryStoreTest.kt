package party.qwer.iris.persistence

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SqliteWebhookDeliveryStoreTest {
    @Test
    fun `enqueue then claim then markSent completes delivery lifecycle`() {
        val (helper, store) = createStore()

        helper.use {
            store.use {
                val delivery =
                    PendingWebhookDelivery(
                        messageId = "message-1",
                        roomId = 100L,
                        route = "default",
                        payloadJson = "{\"text\":\"hello\"}",
                    )

                val id = store.enqueue(delivery)
                val claimed = store.claimReady(limit = 10)

                assertEquals(1, claimed.size)
                assertEquals(
                    ClaimedDelivery(
                        id = id,
                        messageId = delivery.messageId,
                        roomId = delivery.roomId,
                        route = delivery.route,
                        payloadJson = delivery.payloadJson,
                        attemptCount = 0,
                        claimToken = claimed.single().claimToken,
                    ),
                    claimed.single(),
                )

                store.markSent(id, claimed.single().claimToken)

                assertTrue(store.claimReady(limit = 10).isEmpty())
            }
        }
    }

    @Test
    fun `enqueue with duplicate messageId returns existing id`() {
        val (helper, store) = createStore()

        helper.use {
            store.use {
                val firstId =
                    store.enqueue(
                        PendingWebhookDelivery(
                            messageId = "message-1",
                            roomId = 100L,
                            route = "default",
                            payloadJson = "{\"text\":\"hello\"}",
                        ),
                    )

                val secondId =
                    store.enqueue(
                        PendingWebhookDelivery(
                            messageId = "message-1",
                            roomId = 200L,
                            route = "other",
                            payloadJson = "{\"text\":\"ignored\"}",
                        ),
                    )

                assertEquals(firstId, secondId)
            }
        }
    }

    @Test
    fun `markRetry increments attempt and delays next claim`() {
        var now = 1000L
        val (helper, store) = createStore(clock = { now })

        helper.use {
            store.use {
                store.enqueue(
                    PendingWebhookDelivery(
                        messageId = "message-1",
                        roomId = 100L,
                        route = "default",
                        payloadJson = "{\"text\":\"hello\"}",
                    ),
                )

                val firstClaim = store.claimReady(limit = 1).single()
                store.markRetry(
                    id = firstClaim.id,
                    claimToken = firstClaim.claimToken,
                    nextAttemptAt = 6000L,
                    reason = "temporary failure",
                )

                now = 3000L
                assertTrue(store.claimReady(limit = 10).isEmpty())

                now = 6000L
                val secondClaim = store.claimReady(limit = 1).single()
                assertEquals(1, secondClaim.attemptCount)
                assertEquals(firstClaim.id, secondClaim.id)
            }
        }
    }

    @Test
    fun `releaseClaim preserves attempt count and makes entry immediately claimable`() {
        var now = 1000L
        val (helper, store) = createStore(clock = { now })

        helper.use {
            store.use {
                store.enqueue(
                    PendingWebhookDelivery(
                        messageId = "message-1",
                        roomId = 100L,
                        route = "default",
                        payloadJson = "{\"text\":\"hello\"}",
                    ),
                )

                val firstClaim = store.claimReady(limit = 1).single()
                store.releaseClaim(
                    id = firstClaim.id,
                    claimToken = firstClaim.claimToken,
                    nextAttemptAt = now,
                    reason = "graceful shutdown",
                )

                val reclaimed = store.claimReady(limit = 1).single()
                assertEquals(firstClaim.id, reclaimed.id)
                assertEquals(0, reclaimed.attemptCount)
            }
        }
    }

    @Test
    fun `markDead increments attempt count and prevents further claim`() {
        val (helper, store) = createStore()

        helper.use {
            store.use {
                store.enqueue(
                    PendingWebhookDelivery(
                        messageId = "message-1",
                        roomId = 100L,
                        route = "default",
                        payloadJson = "{\"text\":\"hello\"}",
                    ),
                )

                val claim = store.claimReady(limit = 1).single()
                store.markDead(claim.id, claim.claimToken, "permanent failure")

                assertTrue(store.claimReady(limit = 10).isEmpty())
                assertEquals(
                    1L,
                    helper.queryLong(
                        "SELECT attempt_count FROM ${IrisDatabaseSchema.WEBHOOK_OUTBOX_TABLE} WHERE id = ?",
                        claim.id,
                    ),
                )
            }
        }
    }

    @Test
    fun `stale claimToken cannot overwrite a newer claim`() {
        var now = 1000L
        val (helper, store) = createStore(clock = { now })

        helper.use {
            store.use {
                store.enqueue(
                    PendingWebhookDelivery(
                        messageId = "message-1",
                        roomId = 100L,
                        route = "default",
                        payloadJson = "{\"text\":\"hello\"}",
                    ),
                )

                val firstClaim = store.claimReady(limit = 1).single()
                now = 32000L
                assertEquals(1, store.recoverExpiredClaims(olderThanMs = 30000L))

                val secondClaim = store.claimReady(limit = 1).single()

                // stale token으로 markSent → 무시됨
                store.markSent(firstClaim.id, firstClaim.claimToken)

                // entry는 여전히 CLAIMED(secondClaim token) 상태 → claimReady에서 제외
                assertTrue(store.claimReady(limit = 10).isEmpty())

                // 유효한 token으로 markSent 성공
                store.markSent(secondClaim.id, secondClaim.claimToken)
                assertTrue(store.claimReady(limit = 10).isEmpty())
            }
        }
    }

    @Test
    fun `recoverExpiredClaims resets stale CLAIMED entries to RETRY`() {
        var now = 1000L
        val (helper, store) = createStore(clock = { now })

        helper.use {
            store.use {
                repeat(2) { index ->
                    store.enqueue(
                        PendingWebhookDelivery(
                            messageId = "message-${index + 1}",
                            roomId = (index + 1).toLong(),
                            route = "default",
                            payloadJson = "{\"index\":${index + 1}}",
                        ),
                    )
                }

                val firstClaims = store.claimReady(limit = 10)
                assertEquals(2, firstClaims.size)

                now = 32000L
                assertEquals(2, store.recoverExpiredClaims(olderThanMs = 30000L))

                val reclaimed = store.claimReady(limit = 10)
                assertEquals(2, reclaimed.size)
                assertEquals(firstClaims.map { it.id }.sorted(), reclaimed.map { it.id }.sorted())
            }
        }
    }

    @Test
    fun `claimReady respects limit parameter`() {
        val (helper, store) = createStore()

        helper.use {
            store.use {
                repeat(5) { index ->
                    store.enqueue(
                        PendingWebhookDelivery(
                            messageId = "message-${index + 1}",
                            roomId = (index + 1).toLong(),
                            route = "default",
                            payloadJson = "{\"index\":${index + 1}}",
                        ),
                    )
                }

                val firstBatch = store.claimReady(limit = 2)
                val secondBatch = store.claimReady(limit = 10)

                assertEquals(2, firstBatch.size)
                assertEquals(3, secondBatch.size)
            }
        }
    }

    @Test
    fun `already claimed entries are excluded from claimReady`() {
        val (helper, store) = createStore()

        helper.use {
            store.use {
                repeat(2) { index ->
                    store.enqueue(
                        PendingWebhookDelivery(
                            messageId = "message-${index + 1}",
                            roomId = (index + 1).toLong(),
                            route = "default",
                            payloadJson = "{\"index\":${index + 1}}",
                        ),
                    )
                }

                val firstClaim = store.claimReady(limit = 1).single()
                val secondClaim = store.claimReady(limit = 10).single()

                assertTrue(firstClaim.id != secondClaim.id)
            }
        }
    }

    @Test
    fun `claimReady returns entries ordered by id`() {
        val (helper, store) = createStore()

        helper.use {
            store.use {
                repeat(5) { index ->
                    store.enqueue(
                        PendingWebhookDelivery(
                            messageId = "message-${index + 1}",
                            roomId = (index + 1).toLong(),
                            route = "default",
                            payloadJson = "{\"index\":${index + 1}}",
                        ),
                    )
                }

                val claims = store.claimReady(limit = 5)

                assertEquals(claims.map { it.id }.sorted(), claims.map { it.id })
            }
        }
    }

    @Test
    fun `markRetry with stale claimToken is silently ignored`() {
        var now = 1000L
        val (helper, store) = createStore(clock = { now })

        helper.use {
            store.use {
                store.enqueue(
                    PendingWebhookDelivery(
                        messageId = "message-1",
                        roomId = 100L,
                        route = "default",
                        payloadJson = "{\"text\":\"hello\"}",
                    ),
                )

                val firstClaim = store.claimReady(limit = 1).single()
                now = 32000L
                assertEquals(1, store.recoverExpiredClaims(olderThanMs = 30000L))

                val secondClaim = store.claimReady(limit = 1).single()
                store.markRetry(
                    id = firstClaim.id,
                    claimToken = firstClaim.claimToken,
                    nextAttemptAt = 60000L,
                    reason = "stale retry",
                )

                store.markSent(secondClaim.id, secondClaim.claimToken)
                assertTrue(store.claimReady(limit = 10).isEmpty())
            }
        }
    }

    private fun createStore(
        clock: () -> Long = System::currentTimeMillis,
    ): Pair<JdbcSqliteHelper, SqliteWebhookDeliveryStore> {
        val helper = JdbcSqliteHelper.inMemory()
        IrisDatabaseSchema.createWebhookOutboxTable(helper)
        val store = SqliteWebhookDeliveryStore(helper, clock)
        return helper to store
    }
}
