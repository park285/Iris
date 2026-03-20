package party.qwer.iris.bridge

data class RoutingCommand(
    val text: String,
    val room: String,
    val sender: String,
    val userId: String,
    val sourceLogId: Long,
    val chatLogId: String? = null,
    val roomType: String? = null,
    val roomLinkId: String? = null,
    val threadId: String? = null,
    val threadScope: Int? = null,
    val messageType: String? = null,
    val attachment: String? = null,
)

enum class RoutingResult {
    ACCEPTED,
    SKIPPED,
    RETRY_LATER,
}
