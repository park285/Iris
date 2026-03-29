package party.qwer.iris.delivery.webhook

import party.qwer.iris.persistence.PendingWebhookDelivery
import party.qwer.iris.persistence.WebhookDeliveryStore
import java.io.Closeable

interface RoutingGateway : Closeable {
    fun route(command: RoutingCommand): RoutingResult
}

internal class H2cRoutingGateway(
    config: party.qwer.iris.ConfigProvider,
) : RoutingGateway {
    private val dispatcher = H2cDispatcher(config)

    override fun route(command: RoutingCommand): RoutingResult = dispatcher.route(command)

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

    override fun close() {
        deliveryStore.close()
    }
}
