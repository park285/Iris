package party.qwer.iris.bridge

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
}
