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

    @Test
    fun `positive source log id returns kakao log id even with fingerprint fields`() {
        val command =
            RoutingCommand(
                text = "system ping",
                room = "room-1",
                sender = "tester",
                userId = "user-1",
                sourceLogId = 123L,
                chatLogId = "chat-log-9",
                messageType = "notice",
                attachment = """{"image":true}""",
                eventPayload =
                    buildJsonObject {
                        put("type", "nickname_change")
                        put("userId", 123L)
                    },
            )

        assertEquals("kakao-log-123-alerts", buildRoutingMessageId(command, "alerts"))
    }

    @Test
    fun `zero source log id uses ordered system fingerprint with null optional fields`() {
        val command =
            RoutingCommand(
                text = "system ping",
                room = "room-1",
                sender = "iris-system",
                userId = "user-1",
                sourceLogId = 0L,
            )

        assertEquals(
            "kakao-system-0a099065e559a91452c7b69e753371e4967ce62aab2f22d9f88ce1f8f7230c3d-default",
            buildRoutingMessageId(command, "default"),
        )
    }

    @Test
    fun `negative source log id treats blank optional fields as empty fingerprint slots`() {
        val command =
            RoutingCommand(
                text = "system ping",
                room = "room-1",
                sender = "iris-system",
                userId = "user-1",
                sourceLogId = -1L,
                chatLogId = "",
                messageType = "",
                attachment = "",
            )

        assertEquals(
            "kakao-system-0a099065e559a91452c7b69e753371e4967ce62aab2f22d9f88ce1f8f7230c3d-default",
            buildRoutingMessageId(command, "default"),
        )
    }

    @Test
    fun `system fingerprint preserves unicode route room user and text`() {
        val command =
            RoutingCommand(
                text = "안녕 👋",
                room = "방🌙",
                sender = "tester",
                userId = "사용자-1",
                sourceLogId = 0L,
                chatLogId = "로그-0",
                messageType = "notice",
            )

        assertEquals(
            "kakao-system-c450221084d82663c98961e375d00fcd7c522166d10f5fb80fff8c2f286d197f-챗봇",
            buildRoutingMessageId(command, "챗봇"),
        )
    }

    @Test
    fun `system fingerprint includes attachment json string slot`() {
        val command =
            RoutingCommand(
                text = "",
                room = "room",
                sender = "tester",
                userId = "u",
                sourceLogId = 0L,
                messageType = "2",
                attachment = """{"image":true,"name":"a.png"}""",
            )

        assertEquals(
            "kakao-system-9bdca7d08bff39db95a9c4303cda82c159db5609335a0e80dbc5e6e6fdd686da-image",
            buildRoutingMessageId(command, "image"),
        )
    }

    @Test
    fun `system fingerprint preserves structured event payload json ordering`() {
        val eventPayload =
            buildJsonObject {
                put("type", "nickname_change")
                put("userId", 123L)
                put("newNickname", "새별")
            }
        val command =
            RoutingCommand(
                text = """{"type":"nickname_change","userId":123,"newNickname":"새별"}""",
                room = "room",
                sender = "iris-system",
                userId = "0",
                sourceLogId = -1L,
                messageType = "nickname_change",
                eventPayload = eventPayload,
            )

        assertEquals(
            "kakao-system-ca7dda7a51909dcac3bffd81327684d8fa63f0291c56d9bf7a742755061c1398-events",
            buildRoutingMessageId(command, "events"),
        )
    }
}
