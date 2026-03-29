package party.qwer.iris.model

import kotlinx.serialization.Serializable

@Serializable
data class ThreadSummary(
    val threadId: String,
    val originMessage: String? = null,
    val lastMessage: String? = null,
    val messageCount: Int,
    val lastActiveAt: Long,
)

@Serializable
data class ThreadListResponse(
    val chatId: Long,
    val threads: List<ThreadSummary>,
)
