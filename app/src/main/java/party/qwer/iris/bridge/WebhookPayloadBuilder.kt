package party.qwer.iris.bridge

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun buildWebhookPayload(
    command: RoutingCommand,
    route: String,
    messageId: String,
): String =
    buildJsonObject {
        put("route", route)
        put("messageId", messageId)
        put("sourceLogId", command.sourceLogId)
        put("text", command.text)
        put("room", command.room)
        put("sender", command.sender)
        put("userId", command.userId)
        if (!command.chatLogId.isNullOrBlank()) put("chatLogId", command.chatLogId)
        if (!command.roomType.isNullOrBlank()) put("roomType", command.roomType)
        if (!command.roomLinkId.isNullOrBlank()) put("roomLinkId", command.roomLinkId)
        if (!command.threadId.isNullOrBlank()) put("threadId", command.threadId)
        command.threadScope?.let { put("threadScope", it) }
        if (!command.messageType.isNullOrBlank()) put("type", command.messageType)
        if (!command.attachment.isNullOrBlank()) put("attachment", command.attachment)
    }.toString()
