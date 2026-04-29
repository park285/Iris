package party.qwer.iris.delivery.webhook

import party.qwer.iris.persistence.PendingWebhookDelivery
import party.qwer.iris.persistence.WebhookDeliveryStore
import java.io.Closeable

interface RoutingGateway : Closeable {
    fun route(command: RoutingCommand): RoutingResult

    fun isRoutableEventType(messageType: String?): Boolean = resolveEventRoute(messageType) != null

    /**
     * 입력 순서대로 처리한 prefix 결과를 반환합니다.
     * RETRY_LATER가 발생하면 해당 항목까지 반환하고 이후 항목은 시도하지 않습니다.
     */
    fun routeBatch(commands: List<RoutingCommand>): List<RoutingResult> {
        val results = mutableListOf<RoutingResult>()
        for (command in commands) {
            val result = route(command)
            results += result
            if (result == RoutingResult.RETRY_LATER) {
                break
            }
        }
        return results
    }
}

internal class H2cRoutingGateway(
    private val config: party.qwer.iris.ConfigProvider,
) : RoutingGateway {
    private val dispatcher = H2cDispatcher(config)

    override fun route(command: RoutingCommand): RoutingResult = dispatcher.route(command)

    override fun routeBatch(commands: List<RoutingCommand>): List<RoutingResult> = dispatcher.routeBatch(commands)

    override fun isRoutableEventType(messageType: String?): Boolean = resolveEventRoute(messageType, config) != null

    override fun close() {
        dispatcher.close()
    }
}

internal class OutboxRoutingGateway(
    private val config: party.qwer.iris.ConfigProvider,
    private val deliveryStore: WebhookDeliveryStore,
) : RoutingGateway {
    override fun route(command: RoutingCommand): RoutingResult {
        val resolved = resolveWebhookDelivery(command, config) ?: return RoutingResult.SKIPPED
        deliveryStore.enqueue(
            PendingWebhookDelivery(
                messageId = resolved.messageId,
                roomId = resolved.roomId,
                route = resolved.route,
                payloadJson = resolved.payloadJson,
            ),
        )
        return RoutingResult.ACCEPTED
    }

    override fun routeBatch(commands: List<RoutingCommand>): List<RoutingResult> {
        val resolvedDeliveries = resolveWebhookDeliveryPlansBatch(commands, config)
        val results = mutableListOf<RoutingResult>()
        for (resolved in resolvedDeliveries) {
            if (resolved == null) {
                results += RoutingResult.SKIPPED
                continue
            }
            deliveryStore.enqueue(
                PendingWebhookDelivery(
                    messageId = resolved.messageId,
                    roomId = resolved.roomId,
                    route = resolved.route,
                    payloadJson = resolved.payloadJson,
                ),
            )
            results += RoutingResult.ACCEPTED
        }
        return results
    }

    override fun isRoutableEventType(messageType: String?): Boolean = resolveEventRoute(messageType, config) != null

    override fun close() {
        deliveryStore.close()
    }
}
