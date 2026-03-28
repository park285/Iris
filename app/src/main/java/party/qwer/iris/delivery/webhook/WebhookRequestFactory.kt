package party.qwer.iris.delivery.webhook

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import party.qwer.iris.ConfigProvider

internal data class WebhookDelivery(
    val url: String,
    val messageId: String,
    val route: String,
    val payloadJson: String,
    val attempt: Int = 0,
)

internal class WebhookRequestFactory(
    private val config: ConfigProvider,
) {
    fun create(delivery: WebhookDelivery): Request =
        Request
            .Builder()
            .url(delivery.url)
            .post(delivery.payloadJson.toRequestBody(APPLICATION_JSON.toMediaType()))
            .header(HEADER_IRIS_MESSAGE_ID, delivery.messageId)
            .header(HEADER_IRIS_ROUTE, delivery.route)
            .apply {
                val webhookToken = config.webhookToken
                if (webhookToken.isNotBlank()) {
                    header(HEADER_IRIS_TOKEN, webhookToken)
                }
            }.build()

    companion object {
        private const val HEADER_IRIS_TOKEN = "X-Iris-Token"
        private const val HEADER_IRIS_MESSAGE_ID = "X-Iris-Message-Id"
        private const val HEADER_IRIS_ROUTE = "X-Iris-Route"
        private const val APPLICATION_JSON = "application/json"
    }
}
