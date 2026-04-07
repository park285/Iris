package party.qwer.iris.snapshot

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import party.qwer.iris.IrisLogger
import party.qwer.iris.SseEventBus
import party.qwer.iris.delivery.webhook.RoutingCommand
import party.qwer.iris.delivery.webhook.RoutingGateway
import party.qwer.iris.delivery.webhook.RoutingResult
import party.qwer.iris.delivery.webhook.resolveEventRoute
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
            val eventPayload: JsonElement
            val eventUserId: Long
            val eventTimestamp: Long
            when (event) {
                is MemberEvent -> {
                    eventPayload = serverJson.encodeToJsonElement(MemberEvent.serializer(), event)
                    eventUserId = event.userId
                    eventTimestamp = event.timestamp
                }
                is NicknameChangeEvent -> {
                    eventPayload = serverJson.encodeToJsonElement(NicknameChangeEvent.serializer(), event)
                    eventUserId = event.userId
                    eventTimestamp = event.timestamp
                }
                is RoleChangeEvent -> {
                    eventPayload = serverJson.encodeToJsonElement(RoleChangeEvent.serializer(), event)
                    eventUserId = event.userId
                    eventTimestamp = event.timestamp
                }
                is ProfileChangeEvent -> {
                    eventPayload = serverJson.encodeToJsonElement(ProfileChangeEvent.serializer(), event)
                    eventUserId = event.userId
                    eventTimestamp = event.timestamp
                }
            }
            val jsonStr = eventPayload.toString()
            val routingCommand =
                RoutingCommand(
                    text = jsonStr,
                    room = event.chatId.toString(),
                    sender = "iris-system",
                    userId = "0",
                    sourceLogId = -1,
                    messageType = event.type,
                    eventPayload = eventPayload,
                )
            val routingResult = routingGateway?.route(routingCommand) ?: RoutingResult.ACCEPTED
            if (routingResult == RoutingResult.RETRY_LATER) {
                error("retry later for eventType=${event.type}, chatId=${event.chatId}, userId=$eventUserId")
            }
            if (routingResult == RoutingResult.SKIPPED && resolveEventRoute(event.type) != null) {
                error("skipped routable eventType=${event.type}, chatId=${event.chatId}, userId=$eventUserId")
            }

            runCatching {
                eventStore?.insert(
                    chatId = event.chatId,
                    eventType = event.type,
                    userId = eventUserId,
                    payload = jsonStr,
                    createdAtMs = eventTimestamp * 1000,
                )
            }.onFailure { error ->
                IrisLogger.warn(
                    "[SnapshotEventEmitter] Failed to persist room event chatId=${event.chatId}, eventType=${event.type}: ${error.message}",
                )
            }
            runCatching {
                bus.emit(jsonStr)
            }.onFailure { error ->
                IrisLogger.warn(
                    "[SnapshotEventEmitter] Failed to emit SSE room event chatId=${event.chatId}, eventType=${event.type}: ${error.message}",
                )
            }
        }
    }
}
