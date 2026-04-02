package party.qwer.iris.model

import kotlinx.serialization.Serializable

@Serializable
data class QueryRoomSummaryRequest(
    val chatId: Long,
)

@Serializable
data class QueryMemberStatsRequest(
    val chatId: Long,
    val period: String? = null,
    val limit: Int = 20,
    val minMessages: Int = 0,
)

@Serializable
data class QueryRecentThreadsRequest(
    val chatId: Long,
)

@Serializable
data class QueryRecentMessagesRequest(
    val chatId: Long,
    val limit: Int = 50,
)
