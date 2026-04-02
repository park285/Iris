package party.qwer.iris.persistence

import party.qwer.iris.http.SseEventEnvelope

interface SseEventStore {
    fun insert(
        eventType: String,
        payload: String,
        createdAtMs: Long,
    ): Long

    fun replayAfter(
        afterId: Long,
        limit: Int,
    ): List<SseEventEnvelope>

    fun maxId(): Long

    fun prune(keepCount: Int)
}
