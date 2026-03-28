package party.qwer.iris

import kotlin.test.Test
import kotlin.test.assertEquals

class ChatLogDecryptorTest {
    private val noopConfig =
        object : ConfigProvider {
            override val botId = 0L
            override val botName = ""
            override val botSocketPort = 0
            override val botToken = ""
            override val webhookToken = ""
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
}
