package party.qwer.iris.delivery.webhook

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
    private val outboxStore: WebhookOutboxStore,
) : RoutingGateway {
    override fun route(command: RoutingCommand): RoutingResult {
        val resolved = resolveWebhookDelivery(command, config) ?: return RoutingResult.SKIPPED
        return if (
            outboxStore.enqueue(
                PendingWebhookOutboxEntry(
                    roomId = resolved.roomId,
                    route = resolved.route,
                    messageId = resolved.messageId,
                    payloadJson = resolved.payloadJson,
                ),
            )
        ) {
            RoutingResult.ACCEPTED
        } else {
            RoutingResult.RETRY_LATER
        }
    }

    override fun close() {
        outboxStore.close()
    }
}
