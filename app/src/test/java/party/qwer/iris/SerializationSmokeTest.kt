package party.qwer.iris

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import party.qwer.iris.model.CommonErrorResponse
import party.qwer.iris.model.ImageBridgeDiscoveryHook
import party.qwer.iris.model.ImageBridgeHealthCheck
import party.qwer.iris.model.ImageBridgeHealthResult
import party.qwer.iris.model.ReplyAcceptedResponse
import party.qwer.iris.model.ReplyRequest
import party.qwer.iris.model.ReplyType
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * kotlinx.serialization DTO 왕복 직렬화 스모크 테스트.
 *
 * R8 난독화 후 serializer keep 규칙 누락을 감지합니다.
 * proguard-rules.pro의 model.** keep 범위와 쌍을 이루는 검증입니다.
 */
class SerializationSmokeTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    @Test
    fun `ReplyRequest round-trip preserves all fields`() {
        val original =
            ReplyRequest(
                type = ReplyType.TEXT,
                room = "123",
                data = JsonPrimitive("hello"),
                threadId = "456",
                threadScope = 2,
            )
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<ReplyRequest>(encoded)

        assertEquals(original.type, decoded.type)
        assertEquals(original.room, decoded.room)
        assertEquals(original.threadId, decoded.threadId)
        assertEquals(original.threadScope, decoded.threadScope)
    }

    @Test
    fun `ReplyAcceptedResponse round-trip`() {
        val original =
            ReplyAcceptedResponse(
                requestId = "reply-abc",
                room = "789",
                type = ReplyType.IMAGE,
            )
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<ReplyAcceptedResponse>(encoded)

        assertEquals(original.requestId, decoded.requestId)
        assertEquals(original.room, decoded.room)
        assertEquals(original.type, decoded.type)
    }

    @Test
    fun `CommonErrorResponse round-trip`() {
        val original = CommonErrorResponse(message = "test error")
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<CommonErrorResponse>(encoded)

        assertEquals(original.message, decoded.message)
    }

    @Test
    fun `ReplyType enum serializes with SerialName values`() {
        val encoded = json.encodeToString(ReplyType.IMAGE_MULTIPLE)
        assertEquals("\"image_multiple\"", encoded)

        val decoded = json.decodeFromString<ReplyType>("\"image_multiple\"")
        assertEquals(ReplyType.IMAGE_MULTIPLE, decoded)
    }

    @Test
    fun `ImageBridgeHealthResult round-trip`() {
        val original =
            ImageBridgeHealthResult(
                reachable = true,
                running = true,
                specReady = false,
                checkedAtEpochMs = 1234L,
                restartCount = 2,
                lastCrashMessage = "bind failed",
                checks = listOf(ImageBridgeHealthCheck(name = "spec", ok = true)),
                discoveryInstallAttempted = true,
                discoveryHooks =
                    listOf(
                        ImageBridgeDiscoveryHook(
                            name = "hook",
                            installed = true,
                            invocationCount = 1,
                        ),
                    ),
            )
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<ImageBridgeHealthResult>(encoded)

        assertEquals(original.checkedAtEpochMs, decoded.checkedAtEpochMs)
        assertEquals(original.restartCount, decoded.restartCount)
        assertEquals(original.discoveryHooks.single().name, decoded.discoveryHooks.single().name)
    }
}
