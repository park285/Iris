package party.qwer.iris.delivery.webhook

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import party.qwer.iris.DEFAULT_WEBHOOK_ROUTE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebhookPayloadBuilderTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `minimal command produces required fields only`() {
        val command =
            RoutingCommand(
                text = "!test",
                room = "TestRoom",
                sender = "TestUser",
                userId = "12345",
                sourceLogId = 100L,
            )
        val result = buildWebhookPayload(command, DEFAULT_WEBHOOK_ROUTE, "msg-001")
        val obj = json.parseToJsonElement(result).jsonObject

        assertEquals(DEFAULT_WEBHOOK_ROUTE, obj["route"]?.jsonPrimitive?.content)
        assertEquals("msg-001", obj["messageId"]?.jsonPrimitive?.content)
        assertEquals("100", obj["sourceLogId"]?.jsonPrimitive?.content)
        assertEquals("!test", obj["text"]?.jsonPrimitive?.content)
        assertEquals("TestRoom", obj["room"]?.jsonPrimitive?.content)
        assertEquals("TestUser", obj["sender"]?.jsonPrimitive?.content)
        assertEquals("12345", obj["userId"]?.jsonPrimitive?.content)

        assertFalse(obj.containsKey("chatLogId"))
        assertFalse(obj.containsKey("roomType"))
        assertFalse(obj.containsKey("roomLinkId"))
        assertFalse(obj.containsKey("threadId"))
        assertFalse(obj.containsKey("threadScope"))
        assertFalse(obj.containsKey("type"))
        assertFalse(obj.containsKey("eventPayload"))
        assertFalse(obj.containsKey("attachment"))
    }

    @Test
    fun `all optional fields included when present`() {
        val command =
            RoutingCommand(
                text = "!cmd",
                room = "Room",
                sender = "Sender",
                userId = "999",
                sourceLogId = 200L,
                chatLogId = "log-123",
                roomType = "MultiChat",
                roomLinkId = "link-456",
                threadId = "thread-789",
                threadScope = 2,
                messageType = "nickname_change",
                eventPayload =
                    buildJsonObject {
                        put("type", "nickname_change")
                        put("chatId", 18219201472247343L)
                        put("userId", 1234567890L)
                        put("oldNickname", "이전닉")
                        put("newNickname", "변경닉")
                    },
                attachment = "{\"url\":\"http://example.com\"}",
            )
        val result = buildWebhookPayload(command, "settlement", "msg-002")
        val obj = json.parseToJsonElement(result).jsonObject

        assertEquals("settlement", obj["route"]?.jsonPrimitive?.content)
        assertEquals("msg-002", obj["messageId"]?.jsonPrimitive?.content)
        assertEquals("200", obj["sourceLogId"]?.jsonPrimitive?.content)
        assertEquals("log-123", obj["chatLogId"]?.jsonPrimitive?.content)
        assertEquals("MultiChat", obj["roomType"]?.jsonPrimitive?.content)
        assertEquals("link-456", obj["roomLinkId"]?.jsonPrimitive?.content)
        assertEquals("thread-789", obj["threadId"]?.jsonPrimitive?.content)
        assertEquals("2", obj["threadScope"]?.jsonPrimitive?.content)
        assertEquals("nickname_change", obj["type"]?.jsonPrimitive?.content)
        assertEquals(
            "nickname_change",
            obj["eventPayload"]
                ?.jsonObject
                ?.get("type")
                ?.jsonPrimitive
                ?.content,
        )
        assertEquals(
            "변경닉",
            obj["eventPayload"]
                ?.jsonObject
                ?.get("newNickname")
                ?.jsonPrimitive
                ?.content,
        )
        assertEquals("{\"url\":\"http://example.com\"}", obj["attachment"]?.jsonPrimitive?.content)
    }

    @Test
    fun `blank optional fields omitted from payload`() {
        val command =
            RoutingCommand(
                text = "!cmd",
                room = "Room",
                sender = "Sender",
                userId = "999",
                sourceLogId = 201L,
                chatLogId = "",
                roomType = "   ",
                roomLinkId = null,
                threadId = "",
                threadScope = null,
                messageType = "",
                attachment = "   ",
            )
        val result = buildWebhookPayload(command, DEFAULT_WEBHOOK_ROUTE, "msg-003")
        val obj = json.parseToJsonElement(result).jsonObject

        assertFalse(obj.containsKey("chatLogId"))
        assertFalse(obj.containsKey("roomType"))
        assertFalse(obj.containsKey("roomLinkId"))
        assertFalse(obj.containsKey("threadId"))
        assertFalse(obj.containsKey("threadScope"))
        assertFalse(obj.containsKey("type"))
        assertFalse(obj.containsKey("eventPayload"))
        assertFalse(obj.containsKey("attachment"))
    }

    @Test
    fun `special characters properly escaped in JSON`() {
        val command =
            RoutingCommand(
                text = "hello \"world\"\nnew\\line",
                room = "Room",
                sender = "User",
                userId = "1",
                sourceLogId = 1L,
            )
        val result = buildWebhookPayload(command, "test", "id")
        val obj = json.parseToJsonElement(result).jsonObject

        assertEquals("hello \"world\"\nnew\\line", obj["text"]?.jsonPrimitive?.content)
        assertTrue(result.contains("\\\"world\\\""))
        assertTrue(result.contains("\\n"))
        assertTrue(result.contains("new\\\\line"))
    }
}
