package party.qwer.iris.delivery.webhook

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

class RoutingModelsTest {
    @Test
    fun `routing command preserves optional thread metadata`() {
        val command =
            RoutingCommand(
                text = "!ping",
                room = "room-1",
                sender = "tester",
                userId = "user-1",
                sourceLogId = 42L,
                threadId = "thread-7",
            )

        assertEquals("thread-7", command.threadId)
        assertEquals("!ping", command.text)
    }

    @Test
    fun `routing result exposes stable dispatcher outcomes`() {
        assertEquals(
            listOf("ACCEPTED", "SKIPPED", "RETRY_LATER"),
            RoutingResult.entries.map { it.name },
        )
    }

    @Test
    fun `routing command preserves optional message type and attachment`() {
        val command =
            RoutingCommand(
                text = "!ping",
                room = "room-1",
                sender = "tester",
                userId = "user-1",
                sourceLogId = 42L,
                messageType = "1",
                attachment = "{encrypted-data}",
            )

        assertEquals("1", command.messageType)
        assertEquals("{encrypted-data}", command.attachment)
    }

    @Test
    fun `routing command preserves optional structured event payload`() {
        val eventPayload =
            buildJsonObject {
                put("type", "nickname_change")
                put("userId", 123L)
            }
        val command =
            RoutingCommand(
                text = """{"type":"nickname_change"}""",
                room = "room-1",
                sender = "tester",
                userId = "user-1",
                sourceLogId = 42L,
                messageType = "nickname_change",
                eventPayload = eventPayload,
            )

        assertEquals(eventPayload, command.eventPayload)
    }

    @Test
    fun `routing command defaults message type and attachment to null`() {
        val command =
            RoutingCommand(
                text = "!ping",
                room = "room-1",
                sender = "tester",
                userId = "user-1",
                sourceLogId = 42L,
            )

        assertEquals(null, command.messageType)
        assertEquals(null, command.attachment)
        assertEquals(null, command.eventPayload)
    }
}
