package party.qwer.iris.bridge

import kotlin.test.Test
import kotlin.test.assertSame

class H2cDispatcherClientConfigTest {
    @Test
    fun `shares dispatcher and connection pool across transport clients`() {
        H2cDispatcher(transportOverride = "http1").use { dispatcher ->
            val h2cClient = readClientField(dispatcher, "h2cClient")
            val http1Client = readClientField(dispatcher, "http1Client")

            assertSame(h2cClient.dispatcher, http1Client.dispatcher)
            assertSame(h2cClient.connectionPool, http1Client.connectionPool)
        }
    }

    private fun readClientField(
        dispatcher: H2cDispatcher,
        fieldName: String,
    ): okhttp3.OkHttpClient =
        H2cDispatcher::class.java.getDeclaredField(fieldName).let { field ->
            field.isAccessible = true
            field.get(dispatcher) as okhttp3.OkHttpClient
        }
}
