package party.qwer.iris.bridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class H2cDispatcherRouteSupportTest {
    @Test
    fun `routes chatbotgo commands to chatbotgo`() {
        assertEquals("chatbotgo", resolveWebhookRoute("!질문 hello"))
        assertEquals("chatbotgo", resolveWebhookRoute("!이미지 이 사진 뭐야"))
        assertEquals("chatbotgo", resolveWebhookRoute("!리셋"))
        assertEquals("chatbotgo", resolveWebhookRoute("!관리자 상태"))
        assertEquals("chatbotgo", resolveWebhookRoute("!한강"))
    }

    @Test
    fun `routes settlement commands to settlement`() {
        assertEquals("settlement", resolveWebhookRoute("!정산"))
        assertEquals("settlement", resolveWebhookRoute("!정산완료"))
        assertEquals("settlement", resolveWebhookRoute("!정산 완료"))
    }

    @Test
    fun `keeps other webhook commands on hololive`() {
        assertEquals("hololive", resolveWebhookRoute("!ping"))
        assertEquals("hololive", resolveWebhookRoute("/ping"))
    }

    @Test
    fun `returns null for non webhook messages`() {
        assertNull(resolveWebhookRoute("hello"))
    }

    @Test
    fun `routes image messages to chatbotgo`() {
        assertEquals("chatbotgo", resolveImageRoute("2"))
        assertEquals("chatbotgo", resolveImageRoute(" 2 "))
    }

    @Test
    fun `returns null for non image message types`() {
        assertNull(resolveImageRoute("1"))
        assertNull(resolveImageRoute("26"))
        assertNull(resolveImageRoute(null))
        assertNull(resolveImageRoute(""))
    }

    @Test
    fun `creates isolated dispatcher state per route`() {
        val registry = RouteDispatchRegistry { route -> TestState(route) }

        val hololiveA = registry.get("hololive")
        val hololiveB = registry.get("hololive")
        val chatbotgo = registry.get("chatbotgo")

        assertSame(hololiveA, hololiveB)
        assertEquals("hololive", hololiveA.route)
        assertEquals("chatbotgo", chatbotgo.route)
    }

    private data class TestState(
        val route: String,
    )
}
