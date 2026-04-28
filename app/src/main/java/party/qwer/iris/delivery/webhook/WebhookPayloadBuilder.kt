package party.qwer.iris.delivery.webhook

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import party.qwer.iris.nativecore.NativeCoreHolder

internal fun buildWebhookPayload(
    command: RoutingCommand,
    route: String,
    messageId: String,
): String =
    NativeCoreHolder.current().buildWebhookPayloadOrFallback(command, route, messageId) {
        buildWebhookPayloadKotlin(command, route, messageId)
    }

internal fun buildWebhookPayloadKotlin(
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
        command.eventPayload?.let { put("eventPayload", it) }
        if (!command.attachment.isNullOrBlank()) put("attachment", command.attachment)
    }.toString()
