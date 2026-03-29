package party.qwer.iris.snapshot

import kotlinx.serialization.json.Json
import party.qwer.iris.SseEventBus
import party.qwer.iris.delivery.webhook.RoutingCommand
import party.qwer.iris.delivery.webhook.RoutingGateway
import party.qwer.iris.model.MemberEvent
import party.qwer.iris.model.NicknameChangeEvent
import party.qwer.iris.model.ProfileChangeEvent
import party.qwer.iris.model.RoleChangeEvent

open class SnapshotEventEmitter(
    private val bus: SseEventBus,
    private val routingGateway: RoutingGateway?,
) {
    private val serverJson =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    open fun emit(events: List<Any>) {
        for (event in events) {
            val (jsonStr, eventChatId) =
                when (event) {
                    is MemberEvent -> serverJson.encodeToString(MemberEvent.serializer(), event) to event.chatId
                    is NicknameChangeEvent ->
                        serverJson.encodeToString(NicknameChangeEvent.serializer(), event) to event.chatId
                    is RoleChangeEvent ->
                        serverJson.encodeToString(RoleChangeEvent.serializer(), event) to event.chatId
                    is ProfileChangeEvent ->
                        serverJson.encodeToString(ProfileChangeEvent.serializer(), event) to event.chatId
                    else -> continue
                }
            bus.emit(jsonStr)
            routingGateway?.route(
                RoutingCommand(
                    text = jsonStr,
                    room = eventChatId.toString(),
                    sender = "iris-system",
                    userId = "0",
                    sourceLogId = -1,
                    messageType = "member_event",
                ),
            )
        }
    }
}
