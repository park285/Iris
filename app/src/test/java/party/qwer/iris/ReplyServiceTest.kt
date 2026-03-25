package party.qwer.iris

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ReplyServiceTest {
    private val testConfig =
        object : ConfigProvider {
            override val botId = 0L
            override val botName = ""
            override val botSocketPort = 0
            override val botToken = ""
            override val webhookToken = ""
            override val dbPollingRate = 1000L
            override val messageSendRate = 0L

            override fun webhookEndpointFor(route: String) = ""
        }

    @Test
    fun `ReplyQueueKey distinguishes by chatId and threadId`() {
        val key1 = ReplyQueueKey(chatId = 1L, threadId = 100L)
        val key2 = ReplyQueueKey(chatId = 1L, threadId = 100L)
        val key3 = ReplyQueueKey(chatId = 1L, threadId = 200L)
        val key4 = ReplyQueueKey(chatId = 1L, threadId = null)

        assertEquals(key1, key2)
        assertNotEquals(key1, key3)
        assertNotEquals(key1, key4)
        assertNotEquals(key3, key4)
    }

    @Test
    fun `rejects enqueue when max workers exceeded`() {
        val service = ReplyService(testConfig)
        service.start()

        for (i in 0L until 16L) {
            val result = service.sendMessage("ref", chatId = i, msg = "test", threadId = null, threadScope = null)
            assertEquals(ReplyAdmissionStatus.ACCEPTED, result.status, "worker $i should be accepted")
        }

        val overflow = service.sendMessage("ref", chatId = 99L, msg = "test", threadId = null, threadScope = null)
        assertEquals(ReplyAdmissionStatus.QUEUE_FULL, overflow.status)

        service.shutdown()
    }

    @Test
    fun `rejects enqueue after shutdown`() {
        val service = ReplyService(testConfig)
        service.start()
        service.shutdown()

        val result = service.sendMessage("ref", chatId = 1L, msg = "test", threadId = null, threadScope = null)
        assertEquals(ReplyAdmissionStatus.SHUTDOWN, result.status)
    }

    @Test
    fun `rejects enqueue when per-worker queue is full`() {
        val slowConfig =
            object : ConfigProvider {
                override val botId = 0L
                override val botName = ""
                override val botSocketPort = 0
                override val botToken = ""
                override val webhookToken = ""
                override val dbPollingRate = 1000L
                override val messageSendRate = 60_000L

                override fun webhookEndpointFor(route: String) = ""
            }
        val service = ReplyService(slowConfig)
        service.start()

        var fullCount = 0
        for (i in 0 until 20) {
            val result = service.sendMessage("ref", chatId = 1L, msg = "msg$i", threadId = null, threadScope = null)
            if (result.status == ReplyAdmissionStatus.QUEUE_FULL) fullCount++
        }

        assertTrue(fullCount > 0, "at least one enqueue should be rejected as QUEUE_FULL")
        service.shutdown()
    }

    @Test
    fun `shutdown then enqueue returns SHUTDOWN`() {
        val service = ReplyService(testConfig)
        service.start()

        service.sendMessage("ref", chatId = 1L, msg = "drain", threadId = null, threadScope = null)
        service.shutdown()

        val result = service.sendMessage("ref", chatId = 1L, msg = "after", threadId = null, threadScope = null)
        assertEquals(ReplyAdmissionStatus.SHUTDOWN, result.status)
    }
}
