package party.qwer.iris.bridge

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class WebhookDeliveryClient(
    private val clientFactory: WebhookHttpClientFactory,
) {
    suspend fun execute(
        request: Request,
        webhookUrl: String,
    ): Int =
        suspendCancellableCoroutine { continuation ->
            val call = clientFactory.clientFor(webhookUrl).newCall(request)
            continuation.invokeOnCancellation {
                call.cancel()
            }
            call.enqueue(
                object : Callback {
                    override fun onFailure(
                        call: Call,
                        e: IOException,
                    ) {
                        if (!continuation.isActive) {
                            return
                        }
                        continuation.resumeWithException(e)
                    }

                    override fun onResponse(
                        call: Call,
                        response: Response,
                    ) {
                        response.use {
                            if (!continuation.isActive) {
                                return
                            }
                            continuation.resume(it.code)
                        }
                    }
                },
            )
        }
}
