@file:Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")

package party.qwer.iris.bridge

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ImageBridgeRequestHandlerTest {
    @Test
    fun `send image request delegates to runtime and returns sent response`() {
        var captured: ImageSendRequest? = null
        val handler =
            ImageBridgeRequestHandler(
                imageSender = { request -> captured = request },
                healthProvider = { readyHealthSnapshot() },
            )

        val response =
            handler.handle(
                JSONObject().apply {
                    put("action", "send_image")
                    put("roomId", 123L)
                    put("imagePaths", JSONArray(listOf("/tmp/a.png", "/tmp/b.png")))
                    put("threadId", 55L)
                    put("threadScope", 3)
                },
            )

        assertEquals(
            ImageSendRequest(
                roomId = 123L,
                imagePaths = listOf("/tmp/a.png", "/tmp/b.png"),
                threadId = 55L,
                threadScope = 3,
            ),
            captured,
        )
        assertEquals("sent", response.getString("status"))
    }

    @Test
    fun `unknown action returns failed response`() {
        val handler =
            ImageBridgeRequestHandler(
                imageSender = { error("should not be called") },
                healthProvider = { readyHealthSnapshot() },
            )

        val response =
            handler.handle(
                JSONObject().apply {
                    put("action", "unknown")
                },
            )

        assertEquals("failed", response.getString("status"))
        assertEquals("unknown action: unknown", response.getString("error"))
    }

    @Test
    fun `sender failure returns failed response`() {
        val handler =
            ImageBridgeRequestHandler(
                imageSender = { throw IllegalStateException("send failed") },
                healthProvider = { readyHealthSnapshot() },
                logError = { _, _, _ -> },
            )

        val response =
            handler.handle(
                JSONObject().apply {
                    put("action", "send_image")
                    put("roomId", 1L)
                    put("imagePaths", JSONArray(listOf("/tmp/a.png")))
                },
            )

        assertEquals("failed", response.getString("status"))
        assertEquals("send failed", response.getString("error"))
    }

    @Test
    fun `health action returns spec snapshot`() {
        val handler =
            ImageBridgeRequestHandler(
                imageSender = { error("should not be called") },
                healthProvider = {
                    ImageBridgeHealthSnapshot(
                        running = true,
                        specStatus =
                            BridgeSpecStatus(
                                ready = false,
                                checkedAtEpochMs = 1234L,
                                checks = listOf(BridgeSpecCheck(name = "class bh.c", ok = false, detail = "missing")),
                            ),
                        discoverySnapshot =
                            BridgeDiscoverySnapshot(
                                installAttempted = true,
                                hooks =
                                    listOf(
                                        DiscoveryHookStatus(
                                            name = "bh.c#p",
                                            installed = true,
                                            invocationCount = 4,
                                            lastSeenEpochMs = 99L,
                                            lastSummary = "uris=2",
                                        ),
                                    ),
                            ),
                        restartCount = 3,
                        lastCrashMessage = "bind failed",
                    )
                },
            )

        val response =
            handler.handle(
                JSONObject().apply {
                    put("action", party.qwer.iris.ImageBridgeProtocol.ACTION_HEALTH)
                },
            )

        assertEquals(party.qwer.iris.ImageBridgeProtocol.STATUS_OK, response.getString("status"))
        assertFalse(response.getBoolean("specReady"))
        assertEquals(3, response.getInt("restartCount"))
        assertEquals("bind failed", response.getString("lastCrashMessage"))
        assertEquals(1, response.getJSONArray("checks").length())
        assertEquals(1, response.getJSONObject("discovery").getJSONArray("hooks").length())
    }

    @Test
    fun `send image request fails closed when required discovery hook is not installed`() {
        val handler =
            ImageBridgeRequestHandler(
                imageSender = { error("should not be called") },
                healthProvider = {
                    ImageBridgeHealthSnapshot(
                        running = true,
                        specStatus = BridgeSpecStatus(ready = true, checkedAtEpochMs = 1L, checks = emptyList()),
                        discoverySnapshot =
                            BridgeDiscoverySnapshot(
                                installAttempted = true,
                                hooks = listOf(DiscoveryHookStatus(name = "bh.c#n", installed = false, invocationCount = 0)),
                            ),
                        restartCount = 0,
                        lastCrashMessage = null,
                    )
                },
                logError = { _, _, _ -> },
            )

        val response =
            handler.handle(
                JSONObject().apply {
                    put("action", "send_image")
                    put("roomId", 1L)
                    put("imagePaths", JSONArray(listOf("/tmp/a.png")))
                },
            )

        assertEquals("failed", response.getString("status"))
        assertEquals("bridge discovery hook not ready: bh.c#n", response.getString("error"))
    }
}

class KakaoSendInvocationFactoryTest {
    @Test
    fun `sendSingle caches reflection classes across invocations`() {
        FakeMediaSender.reset()
        val lookupCounts = linkedMapOf<String, Int>()
        val classMap =
            mapOf(
                "bh.c" to FakeMediaSender::class.java,
                "com.kakao.talk.model.media.MediaItem" to FakeMediaItem::class.java,
                "kotlin.jvm.functions.Function0" to kotlin.jvm.functions.Function0::class.java,
                "kotlin.jvm.functions.Function1" to kotlin.jvm.functions.Function1::class.java,
            )
        val factory =
            KakaoSendInvocationFactory(
                loader = javaClass.classLoader!!,
                classLookup = { name ->
                    lookupCounts[name] = (lookupCounts[name] ?: 0) + 1
                    classMap[name] ?: error("unexpected class lookup: $name")
                },
            )
        val chatRoom = FakeChatRoom()

        factory.sendSingle(
            chatRoom = chatRoom,
            imagePath = "/tmp/first.png",
            threadId = 7L,
            threadScope = 3,
        )
        factory.sendSingle(
            chatRoom = chatRoom,
            imagePath = "/tmp/second.png",
            threadId = null,
            threadScope = null,
        )

        assertEquals(1, lookupCounts.getValue("bh.c"))
        assertEquals(1, lookupCounts.getValue("com.kakao.talk.model.media.MediaItem"))
        assertEquals(1, lookupCounts.getValue("kotlin.jvm.functions.Function0"))
        assertEquals(1, lookupCounts.getValue("kotlin.jvm.functions.Function1"))
        assertEquals(listOf("/tmp/first.png", "/tmp/second.png"), FakeMediaSender.sentPaths)
        assertEquals(listOf(true, false), FakeMediaSender.threadFlags)
    }

    @Test
    fun `sendSingle rejects missing image path`() {
        val factory =
            KakaoSendInvocationFactory(
                loader = javaClass.classLoader!!,
                classLookup = { error("should not load classes") },
            )

        assertFailsWith<IllegalArgumentException> {
            factory.sendSingle(
                chatRoom = FakeChatRoom(),
                imagePath = "",
                threadId = null,
                threadScope = null,
            )
        }
    }

    @Test
    fun `sendMultiple uses uri list and multi photo enum`() {
        FakeMediaSender.reset()
        val factory =
            KakaoSendInvocationFactory(
                loader = javaClass.classLoader!!,
                classLookup = { name ->
                    when (name) {
                        "bh.c" -> FakeMediaSender::class.java
                        "com.kakao.talk.model.media.MediaItem" -> FakeMediaItem::class.java
                        "kotlin.jvm.functions.Function0" -> kotlin.jvm.functions.Function0::class.java
                        "kotlin.jvm.functions.Function1" -> kotlin.jvm.functions.Function1::class.java
                        "Op.EnumC16810c" -> FakeMessageType::class.java
                        "com.kakao.talk.manager.send.ChatSendingLogRequest\$c" -> FakeWriteType::class.java
                        "com.kakao.talk.manager.send.m" -> FakeListener::class.java
                        else -> error("unexpected class lookup: $name")
                    }
                },
                pathArgumentFactory = { path -> "uri:$path" },
            )

        factory.sendMultiple(
            chatRoom = FakeChatRoom(),
            imagePaths = listOf("/tmp/a.png", "/tmp/b.png"),
            threadId = 1L,
            threadScope = 3,
        )

        assertEquals(listOf("/tmp/a.png", "/tmp/b.png"), FakeMediaSender.multiSentUris)
        assertEquals(FakeMessageType.MultiPhoto, FakeMediaSender.multiType)
        assertEquals(FakeWriteType.None, FakeMediaSender.multiWriteType)
    }
}

class ChatRoomResolverTest {
    @Test
    fun `resolve uses database path and caches class lookups`() {
        FakeChatRuntime.reset()
        val lookupCounts = linkedMapOf<String, AtomicInteger>()
        val resolver =
            ChatRoomResolver(
                loader = javaClass.classLoader!!,
                classLookup = { name ->
                    lookupCounts.getOrPut(name) { AtomicInteger(0) }.incrementAndGet()
                    when (name) {
                        "com.kakao.talk.database.MasterDatabase" -> FakeMasterDatabase::class.java
                        "hp.t" -> FakeChatRoomModel::class.java
                        "hp.J0" -> FakeChatRoomManager::class.java
                        else -> error("unexpected class lookup: $name")
                    }
                },
            )

        val first = resolver.resolve(101L)
        val second = resolver.resolve(102L)

        assertNotNull(first)
        assertNotNull(second)
        assertEquals(1, lookupCounts.getValue("com.kakao.talk.database.MasterDatabase").get())
        assertEquals(1, lookupCounts.getValue("hp.t").get())
        assertEquals(listOf(101L, 102L), FakeChatRuntime.resolvedRoomIds)
    }
}

class ImageBridgeServerRestartPolicyTest {
    @Test
    fun `restart delay grows exponentially and caps`() {
        assertEquals(1_000L, ImageBridgeServer.nextBridgeRestartDelayMs(1))
        assertEquals(2_000L, ImageBridgeServer.nextBridgeRestartDelayMs(2))
        assertEquals(4_000L, ImageBridgeServer.nextBridgeRestartDelayMs(3))
        assertEquals(30_000L, ImageBridgeServer.nextBridgeRestartDelayMs(99))
    }
}

class BridgeDiscoveryTest {
    @Test
    fun `records discovery hook installation and invocation`() {
        BridgeDiscovery.resetForTest()

        BridgeDiscovery.markInstalledForTest("bh.c#p")
        BridgeDiscovery.recordForTest("bh.c#p", "uris=2")

        val snapshot = BridgeDiscovery.snapshot()
        val hook = snapshot.hooks.first { it.name == "bh.c#p" }

        assertTrue(snapshot.installAttempted)
        assertTrue(hook.installed)
        assertEquals(1, hook.invocationCount)
        assertEquals("uris=2", hook.lastSummary)
        assertNotNull(hook.lastSeenEpochMs)
    }
}

class RoomThreadSerialExecutorTest {
    @Test
    fun `same room and thread are serialized`() {
        val executor = RoomThreadSerialExecutor()
        val releaseFirst = CountDownLatch(1)
        val firstStarted = CountDownLatch(1)
        val secondRan = AtomicBoolean(false)
        val pool = Executors.newFixedThreadPool(2)

        try {
            val first =
                pool.submit<Unit> {
                    executor.execute(roomId = 1L, threadId = 10L) {
                        firstStarted.countDown()
                        releaseFirst.await(3, TimeUnit.SECONDS)
                    }
                }
            val second =
                pool.submit<Unit> {
                    firstStarted.await(3, TimeUnit.SECONDS)
                    executor.execute(roomId = 1L, threadId = 10L) {
                        secondRan.set(true)
                    }
                }

            assertFalse(secondRan.get())
            releaseFirst.countDown()
            first.get(3, TimeUnit.SECONDS)
            second.get(3, TimeUnit.SECONDS)
            assertTrue(secondRan.get())
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `different threads in same room can run independently`() {
        val executor = RoomThreadSerialExecutor()
        val releaseFirst = CountDownLatch(1)
        val firstStarted = CountDownLatch(1)
        val secondRan = AtomicBoolean(false)
        val pool = Executors.newFixedThreadPool(2)

        try {
            val first =
                pool.submit<Unit> {
                    executor.execute(roomId = 1L, threadId = 10L) {
                        firstStarted.countDown()
                        releaseFirst.await(3, TimeUnit.SECONDS)
                    }
                }
            val second =
                pool.submit<Unit> {
                    firstStarted.await(3, TimeUnit.SECONDS)
                    executor.execute(roomId = 1L, threadId = 11L) {
                        secondRan.set(true)
                    }
                }

            second.get(3, TimeUnit.SECONDS)
            assertTrue(secondRan.get())
            releaseFirst.countDown()
            first.get(3, TimeUnit.SECONDS)
        } finally {
            pool.shutdownNow()
        }
    }
}

private class FakeChatRoom

private fun readyHealthSnapshot(): ImageBridgeHealthSnapshot =
    ImageBridgeHealthSnapshot(
        running = true,
        specStatus = BridgeSpecStatus(ready = true, checkedAtEpochMs = 1L, checks = emptyList()),
        discoverySnapshot =
            BridgeDiscoverySnapshot(
                installAttempted = true,
                hooks =
                    listOf(
                        DiscoveryHookStatus(name = "bh.c#n", installed = true, invocationCount = 0),
                        DiscoveryHookStatus(name = "bh.c#p", installed = true, invocationCount = 0),
                    ),
            ),
        restartCount = 0,
        lastCrashMessage = null,
    )

private class FakeMediaItem(
    val path: String,
    val size: Long,
)

private class FakeMediaSender(
    chatRoom: FakeChatRoom,
    private val threadId: Long?,
    private val sendWithThread: () -> Boolean,
    private val attachmentDecorator: (JSONObject) -> JSONObject?,
) {
    private val roomHash = chatRoom.hashCode()

    companion object {
        val sentPaths = mutableListOf<String>()
        val threadFlags = mutableListOf<Boolean>()
        val roomHashes = mutableListOf<Int>()
        val multiSentUris = mutableListOf<String>()
        var multiType: Any? = null
        var multiWriteType: Any? = null

        fun reset() {
            sentPaths.clear()
            threadFlags.clear()
            roomHashes.clear()
            multiSentUris.clear()
            multiType = null
            multiWriteType = null
        }
    }

    fun n(
        mediaItem: FakeMediaItem,
        suppressAnimation: Boolean,
    ) {
        sentPaths += mediaItem.path
        threadFlags += sendWithThread()
        roomHashes += roomHash
        check(!suppressAnimation)
        check(attachmentDecorator(JSONObject()) != null)
        check(threadId == null || threadId >= 0L)
    }

    @Suppress("UNUSED_PARAMETER")
    fun p(
        uris: List<Any>,
        type: FakeMessageType,
        message: String?,
        attachment: JSONObject?,
        forwardExtra: JSONObject?,
        writeType: FakeWriteType,
        shareOriginal: Boolean,
        highQuality: Boolean,
        listener: FakeListener?,
    ) {
        multiSentUris += uris.map { uri -> uri.toString().removePrefix("uri:") }
        multiType = type
        multiWriteType = writeType
        check(message == null)
        check(attachment == null)
        check(forwardExtra == null)
        check(!shareOriginal)
        check(!highQuality)
        check(listener == null)
    }
}

private enum class FakeMessageType {
    Photo,
    MultiPhoto,
}

private enum class FakeWriteType {
    None,
}

private interface FakeListener

private object FakeChatRuntime {
    val resolvedRoomIds = mutableListOf<Long>()

    fun reset() {
        resolvedRoomIds.clear()
        FakeMasterDatabase.INSTANCE = FakeMasterDatabase()
    }
}

private class FakeMasterDatabase {
    companion object {
        @JvmField
        var INSTANCE: FakeMasterDatabase? = FakeMasterDatabase()
    }

    @Suppress("FunctionName")
    fun O(): FakeRoomDao = FakeRoomDao()
}

private class FakeRoomDao {
    fun h(roomId: Long): FakeRoomEntity = FakeRoomEntity(roomId)
}

private data class FakeRoomEntity(
    val roomId: Long,
)

private class FakeChatRoomModel private constructor(
    val roomId: Long,
) {
    companion object {
        @JvmField
        val CompanionResolver = Resolver()
    }

    class Resolver {
        fun c(entity: FakeRoomEntity): FakeChatRoomModel {
            FakeChatRuntime.resolvedRoomIds += entity.roomId
            return FakeChatRoomModel(entity.roomId)
        }
    }
}

private class FakeChatRoomManager {
    companion object {
        @JvmStatic
        fun j(): FakeChatRoomManager = FakeChatRoomManager()
    }

    @Suppress("UNUSED_PARAMETER")
    fun e0(
        roomId: Long,
        includeMembers: Boolean,
        includeOpenLink: Boolean,
    ): FakeChatRoomModel? = null

    fun d0(roomId: Long): FakeChatRoomModel = FakeChatRoomModel.CompanionResolver.c(FakeRoomEntity(roomId))
}
