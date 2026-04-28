package party.qwer.iris

import party.qwer.iris.nativecore.NativeCoreHolder
import party.qwer.iris.nativecore.NativeCoreJniBridge
import party.qwer.iris.nativecore.NativeCoreRuntime
import kotlin.test.Test
import kotlin.test.assertEquals

class ChatLogDecryptorTest {
    private val noopConfig =
        object : ConfigProvider {
            override val botId = 0L
            override val botName = ""
            override val botSocketPort = 0
            override val inboundSigningSecret = ""
            override val outboundWebhookToken = ""
            override val botControlToken = ""
            override val dbPollingRate = 1000L
            override val messageSendRate = 0L
            override val messageSendJitterMax = 0L

            override fun webhookEndpointFor(route: String): String = ""
        }

    @Test
    fun `row without decrypt relevant fields returned unchanged`() {
        val row = mapOf("_id" to "1", "chat_id" to "100", "user_id" to "999")

        val result = decryptRow(row, noopConfig)

        assertEquals(row, result)
    }

    @Test
    fun `row with message but no v field returned unchanged`() {
        val row = mapOf("message" to "hello", "user_id" to "999")

        val result = decryptRow(row, noopConfig)

        assertEquals("hello", result["message"])
        assertEquals(row, result)
    }

    @Test
    fun `row with enc but bot id zero skips profile decryption`() {
        val row = mapOf("enc" to "0", "name" to "SomeName", "user_id" to "999")

        val result = decryptRow(row, noopConfig)

        assertEquals("SomeName", result["name"])
        assertEquals(row, result)
    }

    @Test
    fun `empty row returned unchanged`() {
        val row = emptyMap<String, String?>()

        val result = decryptRow(row, noopConfig)

        assertEquals(row, result)
    }

    @Test
    fun `message and attachment fields decrypt through one native batch`() {
        val jni = FakeNativeCoreJni(listOf("plain-message", "plain-attachment"))
        val runtime =
            NativeCoreRuntime.create(
                env = mapOf("IRIS_NATIVE_CORE" to "on"),
                loader = {},
                jni = jni,
            )
        val row =
            mapOf(
                "v" to """{"enc":0}""",
                "user_id" to "123",
                "message" to "encrypted-message",
                "attachment" to "encrypted-attachment",
            )

        try {
            NativeCoreHolder.install(runtime)

            val result = decryptRow(row, noopConfig)

            assertEquals("plain-message", result["message"])
            assertEquals("plain-attachment", result["attachment"])
            assertEquals(1, jni.decryptCalls)
            val decryptStats =
                runtime
                    .diagnostics()
                    .componentStats
                    .getValue("decrypt")
            assertEquals(1L, decryptStats.jniCalls)
            assertEquals(2L, decryptStats.items)
        } finally {
            NativeCoreHolder.resetForTest()
        }
    }

    private class FakeNativeCoreJni(
        private val decryptResults: List<String>,
    ) : NativeCoreJniBridge {
        var decryptCalls = 0
            private set

        override fun nativeSelfTest(): String = "iris-native-core:test"

        override fun decryptBatch(requestJsonBytes: ByteArray): ByteArray {
            decryptCalls += 1
            val items =
                decryptResults.joinToString(separator = ",") { result ->
                    """{"ok":true,"plaintext":"$result"}"""
                }
            return """{"items":[$items]}""".encodeToByteArray()
        }
    }
}
