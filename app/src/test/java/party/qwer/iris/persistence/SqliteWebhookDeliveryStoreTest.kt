package party.qwer.iris.persistence

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
                        failedAttemptCount = 0,
                        claimToken = claimed.single().claimToken,
                    ),
                    claimed.single(),
                )

                assertEquals(ClaimTransitionResult.APPLIED, store.markSent(id, claimed.single().claimToken))

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
    fun `resolveFailure Retry increments attempt and delays next claim`() {
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
                assertEquals(
                    ClaimTransitionResult.APPLIED,
                    store.resolveFailure(
                        id = firstClaim.id,
                        claimToken = firstClaim.claimToken,
                        outcome = FailureOutcome.Retry(nextAttemptAt = 6000L, reason = "temporary failure"),
                    ),
                )

                now = 3000L
                assertTrue(store.claimReady(limit = 10).isEmpty())

                now = 6000L
                val secondClaim = store.claimReady(limit = 1).single()
                assertEquals(1, secondClaim.failedAttemptCount)
                assertEquals(firstClaim.id, secondClaim.id)
            }
        }
    }

    @Test
    fun `requeueClaim preserves attempt count and makes entry immediately claimable`() {
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
                assertEquals(
                    ClaimTransitionResult.APPLIED,
                    store.requeueClaim(
                        id = firstClaim.id,
                        claimToken = firstClaim.claimToken,
                        nextAttemptAt = now,
                        reason = "graceful shutdown",
                    ),
                )

                val reclaimed = store.claimReady(limit = 1).single()
                assertEquals(firstClaim.id, reclaimed.id)
                assertEquals(0, reclaimed.failedAttemptCount)
            }
        }
    }

    @Test
    fun `resolveFailure PermanentFailure increments attempt count and prevents further claim`() {
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
                assertEquals(
                    ClaimTransitionResult.APPLIED,
                    store.resolveFailure(claim.id, claim.claimToken, FailureOutcome.PermanentFailure("permanent failure")),
                )

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
    fun `resolveFailure RejectedBeforeAttempt does not increment attempt count`() {
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
                assertEquals(
                    ClaimTransitionResult.APPLIED,
                    store.resolveFailure(claim.id, claim.claimToken, FailureOutcome.RejectedBeforeAttempt("no webhook URL")),
                )

                assertTrue(store.claimReady(limit = 10).isEmpty())
                assertEquals(
                    0L,
                    helper.queryLong(
                        "SELECT attempt_count FROM ${IrisDatabaseSchema.WEBHOOK_OUTBOX_TABLE} WHERE id = ?",
                        claim.id,
                    ),
                )
                assertEquals(
                    "DEAD",
                    helper
                        .query(
                            "SELECT status FROM ${IrisDatabaseSchema.WEBHOOK_OUTBOX_TABLE} WHERE id = ?",
                            listOf(claim.id),
                        ) { row -> row.getString(0) }
                        .single(),
                )
            }
        }
    }

    @Test
    fun `stale claimToken returns STALE_CLAIM and does not overwrite newer claim`() {
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

                // stale token -> STALE_CLAIM
                assertEquals(ClaimTransitionResult.STALE_CLAIM, store.markSent(firstClaim.id, firstClaim.claimToken))

                // entry는 여전히 CLAIMED(secondClaim token) -> claimReady에서 제외
                assertEquals(
                    "CLAIMED",
                    helper
                        .query(
                            "SELECT status FROM ${IrisDatabaseSchema.WEBHOOK_OUTBOX_TABLE} WHERE id = ?",
                            listOf(firstClaim.id),
                        ) { row -> row.getString(0) }
                        .single(),
                )

                // 유효한 token으로 markSent
                assertEquals(ClaimTransitionResult.APPLIED, store.markSent(secondClaim.id, secondClaim.claimToken))
                assertTrue(store.claimReady(limit = 10).isEmpty())
            }
        }
    }

    @Test
    fun `markSent clears last_error from previous retry`() {
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

                // 1차: claim -> retry (last_error 기록)
                val firstClaim = store.claimReady(limit = 1).single()
                store.resolveFailure(
                    firstClaim.id,
                    firstClaim.claimToken,
                    FailureOutcome.Retry(nextAttemptAt = 2000L, reason = "status=503"),
                )

                assertEquals(
                    "status=503",
                    helper
                        .query(
                            "SELECT last_error FROM ${IrisDatabaseSchema.WEBHOOK_OUTBOX_TABLE} WHERE id = ?",
                            listOf(firstClaim.id),
                        ) { row -> row.getString(0) }
                        .single(),
                )

                // 2차: claim -> markSent (last_error 정리)
                now = 2000L
                val secondClaim = store.claimReady(limit = 1).single()
                store.markSent(secondClaim.id, secondClaim.claimToken)

                val lastError =
                    helper
                        .query(
                            "SELECT last_error FROM ${IrisDatabaseSchema.WEBHOOK_OUTBOX_TABLE} WHERE id = ?",
                            listOf(secondClaim.id),
                        ) { row -> row.getStringOrNull(0) }
                        .single()
                assertTrue(lastError == null, "last_error should be cleared after markSent, got: $lastError")
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
    fun `renewClaim extends claim lease and delays recovery`() {
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

                val claim = store.claimReady(limit = 1).single()

                now = 1500L
                assertEquals(
                    ClaimTransitionResult.APPLIED,
                    store.renewClaim(claim.id, claim.claimToken),
                )

                now = 1800L
                assertEquals(0, store.recoverExpiredClaims(olderThanMs = 600L))

                now = 2200L
                assertEquals(1, store.recoverExpiredClaims(olderThanMs = 600L))
            }
        }
    }

    @Test
    fun `renewClaim with stale claimToken returns STALE_CLAIM`() {
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

                now = 32_000L
                assertEquals(1, store.recoverExpiredClaims(olderThanMs = 30_000L))

                val secondClaim = store.claimReady(limit = 1).single()

                assertEquals(
                    ClaimTransitionResult.STALE_CLAIM,
                    store.renewClaim(firstClaim.id, firstClaim.claimToken),
                )
                assertEquals(
                    ClaimTransitionResult.APPLIED,
                    store.renewClaim(secondClaim.id, secondClaim.claimToken),
                )
            }
        }
    }

    @Test
    fun `claimReady assigns distinct claimToken per row`() {
        val (helper, store) = createStore()

        helper.use {
            store.use {
                repeat(3) { index ->
                    store.enqueue(
                        PendingWebhookDelivery(
                            messageId = "message-${index + 1}",
                            roomId = (index + 1).toLong(),
                            route = "default",
                            payloadJson = "{\"index\":${index + 1}}",
                        ),
                    )
                }

                val claims = store.claimReady(limit = 3)

                assertEquals(3, claims.size)
                val tokens = claims.map { it.claimToken }.toSet()
                assertEquals(3, tokens.size, "each row must have a distinct claimToken, got: $tokens")
            }
        }
    }

    @Test
    fun `resolveFailure with stale claimToken returns STALE_CLAIM`() {
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

                // stale token -> STALE_CLAIM, row 변경 없음
                assertEquals(
                    ClaimTransitionResult.STALE_CLAIM,
                    store.resolveFailure(
                        id = firstClaim.id,
                        claimToken = firstClaim.claimToken,
                        outcome = FailureOutcome.Retry(nextAttemptAt = 60000L, reason = "stale retry"),
                    ),
                )

                // 유효한 token으로 성공 처리
                assertEquals(ClaimTransitionResult.APPLIED, store.markSent(secondClaim.id, secondClaim.claimToken))
                assertTrue(store.claimReady(limit = 10).isEmpty())
            }
        }
    }

    @Test
    fun `requeueClaim with stale claimToken returns STALE_CLAIM`() {
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

                // stale token -> STALE_CLAIM
                assertEquals(
                    ClaimTransitionResult.STALE_CLAIM,
                    store.requeueClaim(
                        id = firstClaim.id,
                        claimToken = firstClaim.claimToken,
                        nextAttemptAt = now,
                        reason = "stale requeue",
                    ),
                )

                // row는 secondClaim의 CLAIMED 상태 유지
                assertEquals(
                    "CLAIMED",
                    helper
                        .query(
                            "SELECT status FROM ${IrisDatabaseSchema.WEBHOOK_OUTBOX_TABLE} WHERE id = ?",
                            listOf(firstClaim.id),
                        ) { row -> row.getString(0) }
                        .single(),
                )

                // 유효한 token으로 성공 처리
                assertEquals(ClaimTransitionResult.APPLIED, store.markSent(secondClaim.id, secondClaim.claimToken))
            }
        }
    }

    @Test
    fun `claimReady excludes stale candidate when claim update affects zero rows`() {
        val driver =
            FakeSqliteDriver(
                queryRows =
                    listOf(
                        listOf(
                            41L,
                            "message-41",
                            100L,
                            "default",
                            """{"text":"hello"}""",
                            0,
                        ),
                    ),
                updateResults = ArrayDeque(listOf(0)),
            )
        val store = SqliteWebhookDeliveryStore(driver) { 1_000L }

        store.use {
            assertTrue(store.claimReady(limit = 10).isEmpty())
        }
    }

    @Test
    fun `enqueue throws explicit error when inserted id cannot be read`() {
        val driver =
            FakeSqliteDriver(
                queryLongResult = null,
                updateResults = ArrayDeque(listOf(1)),
            )
        val store = SqliteWebhookDeliveryStore(driver)

        store.use {
            val error =
                assertFailsWith<IllegalStateException> {
                    store.enqueue(
                        PendingWebhookDelivery(
                            messageId = "message-1",
                            roomId = 100L,
                            route = "default",
                            payloadJson = """{"text":"hello"}""",
                        ),
                    )
                }
            assertTrue(error.message.orEmpty().contains("failed to read webhook delivery id"))
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

private class FakeSqliteDriver(
    private val queryLongResult: Long? = 1L,
    private val queryRows: List<List<Any?>> = emptyList(),
    private val updateResults: ArrayDeque<Int> = ArrayDeque(),
) : SqliteDriver {
    override fun execute(sql: String) = Unit

    override fun queryLong(
        sql: String,
        vararg args: Any?,
    ): Long? = queryLongResult

    override fun <T> query(
        sql: String,
        args: List<Any?>,
        mapRow: (SqliteRow) -> T,
    ): List<T> = queryRows.map { rowValues -> mapRow(FakeSqliteRow(rowValues)) }

    override fun update(
        sql: String,
        args: List<Any?>,
    ): Int = updateResults.removeFirstOrNull() ?: 1

    override fun <T> inImmediateTransaction(block: SqliteDriver.() -> T): T = block()

    override fun close() = Unit
}

private class FakeSqliteRow(
    private val values: List<Any?>,
) : SqliteRow {
    override fun getLong(columnIndex: Int): Long = values[columnIndex] as Long

    override fun getString(columnIndex: Int): String = values[columnIndex] as String

    override fun getStringOrNull(columnIndex: Int): String? = values[columnIndex] as String?

    override fun getInt(columnIndex: Int): Int = values[columnIndex] as Int

    override fun isNull(columnIndex: Int): Boolean = values[columnIndex] == null
}
