package party.qwer.iris.snapshot

import kotlinx.serialization.json.Json
import party.qwer.iris.SseEventBus
import party.qwer.iris.delivery.webhook.RoutingCommand
import party.qwer.iris.delivery.webhook.RoutingGateway
import party.qwer.iris.model.MemberEvent
import party.qwer.iris.model.NicknameChangeEvent
import party.qwer.iris.model.ProfileChangeEvent
import party.qwer.iris.model.RoleChangeEvent
import party.qwer.iris.model.RoomEvent
import party.qwer.iris.persistence.RoomEventStore

open class SnapshotEventEmitter(
    private val bus: SseEventBus,
    private val routingGateway: RoutingGateway?,
    private val eventStore: RoomEventStore? = null,
) {
    private val serverJson =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    open fun emit(events: List<RoomEvent>) {
        for (event in events) {
            val jsonStr: String
            val eventUserId: Long
            val eventTimestamp: Long
            when (event) {
                is MemberEvent -> {
                    jsonStr = serverJson.encodeToString(MemberEvent.serializer(), event)
                    eventUserId = event.userId
                    eventTimestamp = event.timestamp
                }
                is NicknameChangeEvent -> {
                    jsonStr = serverJson.encodeToString(NicknameChangeEvent.serializer(), event)
                    eventUserId = event.userId
                    eventTimestamp = event.timestamp
                }
                is RoleChangeEvent -> {
                    jsonStr = serverJson.encodeToString(RoleChangeEvent.serializer(), event)
                    eventUserId = event.userId
                    eventTimestamp = event.timestamp
                }
                is ProfileChangeEvent -> {
                    jsonStr = serverJson.encodeToString(ProfileChangeEvent.serializer(), event)
                    eventUserId = event.userId
                    eventTimestamp = event.timestamp
                }
            }
            eventStore?.insert(
                chatId = event.chatId,
                eventType = event.type,
                userId = eventUserId,
                payload = jsonStr,
                createdAtMs = eventTimestamp,
            )
            bus.emit(jsonStr)
            routingGateway?.route(
                RoutingCommand(
                    text = jsonStr,
                    room = event.chatId.toString(),
                    sender = "iris-system",
                    userId = "0",
                    sourceLogId = -1,
                    messageType = "member_event",
                ),
            )
        }
    }
}
