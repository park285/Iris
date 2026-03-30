@file:Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")

package party.qwer.iris.imagebridge.runtime

import org.json.JSONObject
import party.qwer.iris.ImageBridgeProtocol
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
        val file = Files.createTempFile("iris-bridge", ".png").toFile().apply { writeText("x") }
        val rootDir = file.parentFile ?: error("temp file parent missing")
        val handler =
            ImageBridgeRequestHandler(
                imageSender = { request -> captured = request },
                healthProvider = { readyHealthSnapshot() },
                handshakeValidator = developmentHandshakeValidator(),
                pathValidator = BridgeImagePathValidator(rootDir.absolutePath),
            )

        val response =
            handler.handle(
                sendImageRequest(
                    roomId = 123L,
                    imagePaths = listOf(file.absolutePath),
                    threadId = 55L,
                    threadScope = 3,
                    requestId = "req-1",
                ),
            )

        assertEquals(
            ImageSendRequest(
                roomId = 123L,
                imagePaths = listOf(file.canonicalPath),
                threadId = 55L,
                threadScope = 3,
                requestId = "req-1",
            ),
            captured,
        )
        assertEquals("sent", response.status)
        file.delete()
    }

    @Test
    fun `unknown action returns failed response`() {
        val handler =
            ImageBridgeRequestHandler(
                imageSender = { error("should not be called") },
                healthProvider = { readyHealthSnapshot() },
                handshakeValidator = developmentHandshakeValidator(),
            )

        val response =
            handler.handle(
                ImageBridgeProtocol.ImageBridgeRequest(
                    action = "unknown",
                    protocolVersion = ImageBridgeProtocol.PROTOCOL_VERSION,
                ),
            )

        assertEquals("failed", response.status)
        assertEquals("unknown action: unknown", response.error)
    }

    @Test
    fun `sender failure returns failed response`() {
        val file = Files.createTempFile("iris-bridge", ".png").toFile().apply { writeText("x") }
        val rootDir = file.parentFile ?: error("temp file parent missing")
        val handler =
            ImageBridgeRequestHandler(
                imageSender = { throw IllegalStateException("send failed") },
                healthProvider = { readyHealthSnapshot() },
                handshakeValidator = developmentHandshakeValidator(),
                pathValidator = BridgeImagePathValidator(rootDir.absolutePath),
                logError = { _, _, _ -> },
            )

        val response =
            handler.handle(
                sendImageRequest(roomId = 1L, imagePaths = listOf(file.absolutePath)),
            )

        assertEquals("failed", response.status)
        assertEquals("send failed", response.error)
        file.delete()
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
                                            name = HOOK_SEND_MULTIPLE,
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
                handshakeValidator = developmentHandshakeValidator(),
            )

        val response =
            handler.handle(
                healthRequest(),
            )

        assertEquals(party.qwer.iris.ImageBridgeProtocol.STATUS_OK, response.status)
        assertFalse(response.specReady == true)
        assertEquals(3, response.restartCount)
        assertEquals("bind failed", response.lastCrashMessage)
        assertEquals(1, response.checks.size)
        assertEquals(1, response.discovery?.hooks?.size)
    }

    @Test
    fun `send image request fails closed when required discovery hook is not installed`() {
        val file = Files.createTempFile("iris-bridge", ".png").toFile().apply { writeText("x") }
        val rootDir = file.parentFile ?: error("temp file parent missing")
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
                                hooks = listOf(DiscoveryHookStatus(name = HOOK_SEND_SINGLE, installed = false, invocationCount = 0)),
                            ),
                        restartCount = 0,
                        lastCrashMessage = null,
                    )
                },
                handshakeValidator = developmentHandshakeValidator(),
                pathValidator = BridgeImagePathValidator(rootDir.absolutePath),
                logError = { _, _, _ -> },
            )

        val response =
            handler.handle(
                sendImageRequest(roomId = 1L, imagePaths = listOf(file.absolutePath)),
            )

        assertEquals("failed", response.status)
        assertEquals("bridge discovery hook not ready: ChatMediaSender#sendSingle", response.error)
        file.delete()
    }

    @Test
    fun `send image request fails when discovery installation never ran`() {
        val file = Files.createTempFile("iris-bridge", ".png").toFile().apply { writeText("x") }
        val rootDir = file.parentFile ?: error("temp file parent missing")
        val handler =
            ImageBridgeRequestHandler(
                imageSender = { error("should not be called") },
                healthProvider = {
                    ImageBridgeHealthSnapshot(
                        running = true,
                        specStatus = BridgeSpecStatus(ready = true, checkedAtEpochMs = 1L, checks = emptyList()),
                        discoverySnapshot = BridgeDiscoverySnapshot(installAttempted = false, hooks = emptyList()),
                        restartCount = 0,
                        lastCrashMessage = null,
                    )
                },
                handshakeValidator = developmentHandshakeValidator(),
                pathValidator = BridgeImagePathValidator(rootDir.absolutePath),
                logError = { _, _, _ -> },
            )

        val response =
            handler.handle(
                sendImageRequest(roomId = 1L, imagePaths = listOf(file.absolutePath)),
            )

        assertEquals("failed", response.status)
        assertEquals("bridge discovery hooks not installed", response.error)
        file.delete()
    }

    @Test
    fun `threaded send image request fails closed when threaded discovery hook is not installed`() {
        val file = Files.createTempFile("iris-bridge", ".png").toFile().apply { writeText("x") }
        val rootDir = file.parentFile ?: error("temp file parent missing")
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
                                hooks =
                                    listOf(
                                        DiscoveryHookStatus(name = HOOK_SEND_SINGLE, installed = true, invocationCount = 0),
                                        DiscoveryHookStatus(name = HOOK_SEND_THREADED_ENTRY, installed = false, invocationCount = 0),
                                    ),
                            ),
                        restartCount = 0,
                        lastCrashMessage = null,
                    )
                },
                handshakeValidator = developmentHandshakeValidator(),
                pathValidator = BridgeImagePathValidator(rootDir.absolutePath),
                logError = { _, _, _ -> },
            )

        val response =
            handler.handle(
                sendImageRequest(
                    roomId = 1L,
                    imagePaths = listOf(file.absolutePath),
                    threadId = 55L,
                    threadScope = 2,
                ),
            )

        assertEquals("failed", response.status)
        assertEquals("bridge discovery hook not ready: ChatMediaSender#threadedEntry", response.error)
        file.delete()
    }

    @Test
    fun `request fails when protocol version is missing`() {
        val handler =
            ImageBridgeRequestHandler(
                imageSender = { error("should not be called") },
                healthProvider = { readyHealthSnapshot() },
                handshakeValidator = developmentHandshakeValidator(),
                logError = { _, _, _ -> },
            )

        val response =
            handler.handle(
                ImageBridgeProtocol.ImageBridgeRequest(
                    action = "health",
                    protocolVersion = null,
                ),
            )

        assertEquals("failed", response.status)
        assertEquals("unsupported protocol version", response.error)
    }

    @Test
    fun `request fails when bridge token does not match`() {
        val handler =
            ImageBridgeRequestHandler(
                imageSender = { error("should not be called") },
                healthProvider = { readyHealthSnapshot() },
                handshakeValidator = BridgeHandshakeValidator(expectedToken = "bridge-token"),
                logError = { _, _, _ -> },
            )

        val response =
            handler.handle(
                healthRequest(token = "wrong-token"),
            )

        assertEquals("failed", response.status)
        assertEquals("unauthorized bridge token", response.error)
    }
}

private fun sendImageRequest(
    roomId: Long,
    imagePaths: List<String>,
    threadId: Long? = null,
    threadScope: Int? = null,
    requestId: String? = null,
    token: String? = null,
): ImageBridgeProtocol.ImageBridgeRequest =
    ImageBridgeProtocol.buildSendImageRequest(
        roomId = roomId,
        imagePaths = imagePaths,
        threadId = threadId,
        threadScope = threadScope,
        requestId = requestId,
        token = token,
    )

private fun healthRequest(token: String? = null): ImageBridgeProtocol.ImageBridgeRequest = ImageBridgeProtocol.buildHealthRequest(token = token)

private fun developmentHandshakeValidator(): BridgeHandshakeValidator =
    BridgeHandshakeValidator(
        expectedToken = "",
        securityMode = BridgeSecurityMode.DEVELOPMENT,
    )

class KakaoSendInvocationFactoryTest {
    @Test
    fun `sendSingle caches reflection classes across invocations`() {
        FakeMediaSender.reset()
        val factory =
            KakaoSendInvocationFactory(
                registry = buildFakeRegistry(),
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

        assertEquals(listOf("/tmp/first.png", "/tmp/second.png"), FakeMediaSender.sentPaths)
        assertEquals(listOf(true, false), FakeMediaSender.threadFlags)
    }

    @Test
    fun `sendSingle rejects missing image path`() {
        val factory =
            KakaoSendInvocationFactory(
                registry = buildFakeRegistry(),
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
                registry = buildFakeRegistry(),
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

    @Test
    fun `sendSingle resolves sender constructor from assignable chatRoom parameter`() {
        FakePolymorphicMediaSender.reset()
        val factory =
            KakaoSendInvocationFactory(
                registry = buildPolymorphicRegistry(),
            )

        factory.sendSingle(
            chatRoom = FakeDerivedChatRoom(),
            imagePath = "/tmp/polymorphic.png",
            threadId = null,
            threadScope = null,
        )

        assertEquals(listOf("/tmp/polymorphic.png"), FakePolymorphicMediaSender.sentPaths)
    }

    @Test
    fun `sendSingle prefers exact sender constructor over assignable one`() {
        ExactPreferredMediaSender.reset()
        val factory =
            KakaoSendInvocationFactory(
                registry = buildExactPreferredRegistry(),
            )

        factory.sendSingle(
            chatRoom = FakeDerivedChatRoom(),
            imagePath = "/tmp/exact.png",
            threadId = null,
            threadScope = null,
        )

        assertEquals(1, ExactPreferredMediaSender.exactCalls)
        assertEquals(0, ExactPreferredMediaSender.baseCalls)
    }
}

class ThreadedChatMediaEntryInvokerTest {
    @Test
    fun `threaded entry invoker resolves method by signature when obfuscated name changes`() {
        RenamedThreadedEntryMediaSender.reset()
        val invoker =
            ThreadedChatMediaEntryInvoker(
                registry = buildRenamedThreadedRegistry(),
                pathArgumentFactory = { path -> "uri:$path" },
            )

        invoker.invoke(
            sender =
                RenamedThreadedEntryMediaSender(
                    chatRoom = FakeChatRoom(),
                    threadId = 3805486995143352321L,
                    sendWithThread = { false },
                    attachmentDecorator = { payload -> payload },
                ),
            imagePaths = listOf("/tmp/thread-a.png", "/tmp/thread-b.png"),
        )

        assertEquals(listOf("/tmp/thread-a.png", "/tmp/thread-b.png"), RenamedThreadedEntryMediaSender.sentUris)
        assertEquals(FakeMessageType.MultiPhoto, RenamedThreadedEntryMediaSender.lastType)
        assertEquals(FakeWriteType.Connect, RenamedThreadedEntryMediaSender.lastWriteType)
    }
}

class ThreadedImageXposedInjectorSelectorTest {
    @Test
    fun `threaded image injector selector resolves method by signature when obfuscated name changes`() {
        val method =
            selectThreadedImageInjectMethodForTest(
                chatMediaSenderClass = RenamedThreadedInjectMediaSender::class.java,
                writeTypeClass = FakeWriteType::class.java,
                listenerClass = FakeListener::class.java,
            )

        assertEquals("z", method.name)
    }

    @Test
    fun `threaded image injector prefers request dispatch hook when available`() {
        val bindings =
            selectThreadedImageInjectBindingsForTest(
                requestCompanionClass = FakeThreadedRequestCompanion::class.java,
                chatMediaSenderClass = RenamedThreadedInjectMediaSender::class.java,
                chatRoomClass = FakeChatRoomModel::class.java,
                writeTypeClass = FakeWriteType::class.java,
                listenerClass = FakeListener::class.java,
            )

        assertEquals(listOf("request", "legacy"), bindings.map { it.source })
        assertEquals(listOf("u", "z"), bindings.map { it.method.name })
        assertEquals(listOf(1, 0), bindings.map { it.sendingLogArgIndex })
    }
}

class ChatRoomResolverTest {
    @Test
    fun `resolve uses database path with registry`() {
        FakeChatRuntime.reset()
        val registry = buildFakeRegistry()
        val resolver = ChatRoomResolver(registry = registry)

        val first = resolver.resolve(101L)
        val second = resolver.resolve(102L)

        assertNotNull(first)
        assertNotNull(second)
        assertEquals(listOf(101L, 102L), FakeChatRuntime.resolvedRoomIds)
    }

    @Test
    fun `resolve prefers exact legacy companion resolver name`() {
        FakeChatRuntime.reset()
        LegacyNameSensitiveRecorder.calls.clear()
        val resolver = ChatRoomResolver(registry = buildLegacyNameSensitiveRegistry())

        resolver.resolve(777L)

        assertEquals(listOf("c"), LegacyNameSensitiveRecorder.calls)
    }
}

class KakaoImageSenderTest {
    @Test
    fun `threaded image send routes through threaded invoker`() {
        val invoker = RecordingKakaoSendInvoker()
        val sender =
            KakaoImageSender(
                chatRoomResolver = { FakeChatRoom() },
                sendInvocationFactory = invoker,
                logInfo = { _, _ -> },
            )

        sender.send(
            roomId = 18478615493603057L,
            imagePaths = listOf("/tmp/thread.png"),
            threadId = 3805486995143352321L,
            threadScope = 3,
            requestId = "req-thread",
        )

        assertEquals(0, invoker.singleCalls)
        assertEquals(0, invoker.multiCalls)
        assertEquals(1, invoker.threadedCalls)
        assertEquals(18478615493603057L, invoker.lastRoomId)
        assertEquals(listOf("/tmp/thread.png"), invoker.lastImagePaths)
        assertEquals(3805486995143352321L, invoker.lastThreadId)
        assertEquals(3, invoker.lastThreadScope)
    }

    @Test
    fun `room image send still routes through single invoker`() {
        val invoker = RecordingKakaoSendInvoker()
        val sender =
            KakaoImageSender(
                chatRoomResolver = { FakeChatRoom() },
                sendInvocationFactory = invoker,
                logInfo = { _, _ -> },
            )

        sender.send(
            roomId = 18478615493603057L,
            imagePaths = listOf("/tmp/room.png"),
            threadId = null,
            threadScope = null,
            requestId = "req-room",
        )

        assertEquals(1, invoker.singleCalls)
        assertEquals(0, invoker.multiCalls)
        assertEquals(0, invoker.threadedCalls)
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

    @Test
    fun `client executor uses bounded pool and queue`() {
        val executor = ImageBridgeServer.newClientExecutorForTest()

        try {
            assertEquals(2, executor.corePoolSize)
            assertEquals(8, executor.maximumPoolSize)
            assertEquals(64, executor.queue.remainingCapacity())
        } finally {
            executor.shutdownNow()
        }
    }
}

class BridgeDiscoveryTest {
    @Test
    fun `records discovery hook installation and invocation`() {
        BridgeDiscovery.resetForTest()

        BridgeDiscovery.markInstalledForTest(HOOK_SEND_MULTIPLE)
        BridgeDiscovery.recordForTest(HOOK_SEND_MULTIPLE, "uris=2")

        val snapshot = BridgeDiscovery.snapshot()
        val hook = snapshot.hooks.first { it.name == HOOK_SEND_MULTIPLE }

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

    @Test
    fun `lock count stays bounded across many keys`() {
        val executor = RoomThreadSerialExecutor(stripeCount = 8)

        repeat(100) { index ->
            executor.execute(roomId = index.toLong(), threadId = index.toLong()) {}
        }

        assertEquals(8, executor.lockCountForTest())
    }
}

class BridgeSecurityTest {
    @Test
    fun `security mode defaults to production unless development is explicitly requested`() {
        assertEquals(BridgeSecurityMode.PRODUCTION, BridgeSecurityMode.fromEnv(null))
        assertEquals(BridgeSecurityMode.PRODUCTION, BridgeSecurityMode.fromEnv("unknown"))
        assertEquals(BridgeSecurityMode.DEVELOPMENT, BridgeSecurityMode.fromEnv("development"))
    }

    @Test
    fun `peer validator rejects unauthorized uid`() {
        val validator = BridgePeerIdentityValidator(setOf(2000))

        val error =
            assertFailsWith<IllegalArgumentException> {
                validator.validate(1000)
            }

        assertEquals("unauthorized bridge client uid=1000", error.message)
    }

    @Test
    fun `production mode requires configured token`() {
        val validator =
            BridgeHandshakeValidator(
                expectedToken = "",
                securityMode = BridgeSecurityMode.PRODUCTION,
            )

        val error =
            assertFailsWith<IllegalArgumentException> {
                validator.validate(healthRequest(token = ""))
            }

        assertEquals("bridge token must be configured in production mode", error.message)
    }

    @Test
    fun `production mode rejects mismatched token`() {
        val validator =
            BridgeHandshakeValidator(
                expectedToken = "secret",
                securityMode = BridgeSecurityMode.PRODUCTION,
            )

        val error =
            assertFailsWith<IllegalArgumentException> {
                validator.validate(healthRequest(token = "wrong"))
            }

        assertEquals("unauthorized bridge token", error.message)
    }

    @Test
    fun `production mode accepts matching token`() {
        val validator =
            BridgeHandshakeValidator(
                expectedToken = "secret",
                securityMode = BridgeSecurityMode.PRODUCTION,
            )

        validator.validate(healthRequest(token = "secret"))
    }

    @Test
    fun `development mode skips token check when blank`() {
        val validator =
            BridgeHandshakeValidator(
                expectedToken = "",
                securityMode = BridgeSecurityMode.DEVELOPMENT,
            )

        validator.validate(healthRequest(token = ""))
    }

    @Test
    fun `development mode checks token when configured`() {
        val validator =
            BridgeHandshakeValidator(
                expectedToken = "secret",
                securityMode = BridgeSecurityMode.DEVELOPMENT,
            )

        val error =
            assertFailsWith<IllegalArgumentException> {
                validator.validate(healthRequest(token = "wrong"))
            }

        assertEquals("unauthorized bridge token", error.message)
    }

    @Test
    fun `default allowed uids include configured values in addition to development defaults`() {
        val allowed = BridgePeerIdentityValidator.defaultAllowedUids("2000, 3000")

        assertTrue(allowed.contains(0))
        assertTrue(allowed.contains(2000))
        assertTrue(allowed.contains(3000))
    }

    @Test
    fun `production mode rejects root uid without explicit allowlist`() {
        val validator =
            BridgePeerIdentityValidator(
                securityMode = BridgeSecurityMode.PRODUCTION,
                extraUidsRaw = null,
            )

        val error =
            assertFailsWith<IllegalArgumentException> {
                validator.validate(0)
            }

        assertEquals("unauthorized bridge client uid=0", error.message)
    }

    @Test
    fun `production mode accepts explicitly allowed uid`() {
        val validator =
            BridgePeerIdentityValidator(
                securityMode = BridgeSecurityMode.PRODUCTION,
                extraUidsRaw = "0,2000",
            )

        validator.validate(0)
    }

    @Test
    fun `development mode allows root and shell by default`() {
        val validator =
            BridgePeerIdentityValidator(
                securityMode = BridgeSecurityMode.DEVELOPMENT,
                extraUidsRaw = null,
            )

        validator.validate(0)
        validator.validate(2000)
    }

    @Test
    fun `path validator rejects files outside allowed root`() {
        val allowedDir = Files.createTempDirectory("iris-allowed").toFile()
        val outsideFile = Files.createTempFile("iris-outside", ".png").toFile().apply { writeText("x") }
        val validator = BridgeImagePathValidator(allowedDir.absolutePath)

        val error =
            assertFailsWith<IllegalArgumentException> {
                validator.validate(listOf(outsideFile.absolutePath))
            }

        assertTrue(error.message?.contains("outside allowed root") == true)
        outsideFile.delete()
        allowedDir.deleteRecursively()
    }

    @Test
    fun `path validator accepts files inside allowed root`() {
        val allowedDir = Files.createTempDirectory("iris-allowed").toFile()
        val insideFile = Files.createTempFile(allowedDir.toPath(), "iris-inside", ".png").toFile().apply { writeText("x") }
        val validator = BridgeImagePathValidator(allowedDir.absolutePath)

        val validated = validator.validate(listOf(insideFile.absolutePath))

        assertEquals(listOf(insideFile.canonicalPath), validated)
        insideFile.delete()
        allowedDir.deleteRecursively()
    }
}

private class FakeChatRoom

private class RecordingKakaoSendInvoker : KakaoSendInvoker {
    var singleCalls = 0
    var multiCalls = 0
    var threadedCalls = 0
    var lastRoomId: Long? = null
    var lastImagePaths: List<String> = emptyList()
    var lastThreadId: Long? = null
    var lastThreadScope: Int? = null

    override fun sendSingle(
        chatRoom: Any,
        imagePath: String,
        threadId: Long?,
        threadScope: Int?,
    ) {
        singleCalls += 1
    }

    override fun sendMultiple(
        chatRoom: Any,
        imagePaths: List<String>,
        threadId: Long?,
        threadScope: Int?,
    ) {
        multiCalls += 1
    }

    override fun sendThreaded(
        roomId: Long,
        chatRoom: Any,
        imagePaths: List<String>,
        threadId: Long,
        threadScope: Int,
    ) {
        threadedCalls += 1
        lastRoomId = roomId
        lastImagePaths = imagePaths
        lastThreadId = threadId
        lastThreadScope = threadScope
    }
}

private fun readyHealthSnapshot(): ImageBridgeHealthSnapshot =
    ImageBridgeHealthSnapshot(
        running = true,
        specStatus = BridgeSpecStatus(ready = true, checkedAtEpochMs = 1L, checks = emptyList()),
        discoverySnapshot =
            BridgeDiscoverySnapshot(
                installAttempted = true,
                hooks =
                    listOf(
                        DiscoveryHookStatus(name = HOOK_SEND_SINGLE, installed = true, invocationCount = 0),
                        DiscoveryHookStatus(name = HOOK_SEND_MULTIPLE, installed = true, invocationCount = 0),
                        DiscoveryHookStatus(name = HOOK_SEND_THREADED_ENTRY, installed = true, invocationCount = 0),
                        DiscoveryHookStatus(name = HOOK_SEND_THREADED_INJECT, installed = true, invocationCount = 0),
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
    Connect,
}

private interface FakeListener

private class RenamedThreadedEntryMediaSender(
    chatRoom: FakeChatRoom,
    private val threadId: Long?,
    private val sendWithThread: () -> Boolean,
    private val attachmentDecorator: (JSONObject) -> JSONObject?,
) {
    companion object {
        val sentUris = mutableListOf<String>()
        var lastType: FakeMessageType? = null
        var lastWriteType: FakeWriteType? = null

        fun reset() {
            sentUris.clear()
            lastType = null
            lastWriteType = null
        }
    }

    init {
        check(chatRoom.hashCode() != 0 || chatRoom.hashCode() == 0)
        check(threadId != null)
        check(!sendWithThread())
        check(attachmentDecorator(JSONObject()) != null)
    }

    fun n(
        mediaItem: FakeMediaItem,
        suppressAnimation: Boolean,
    ) {
        check(mediaItem.path.isNotBlank())
        check(!suppressAnimation)
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
        check(uris.isNotEmpty() || message == null)
        check(type.name.isNotBlank())
        check(attachment == null)
        check(forwardExtra == null)
        check(writeType.name.isNotBlank())
        check(!shareOriginal)
        check(!highQuality)
        check(listener == null)
    }

    @Suppress("UNUSED_PARAMETER")
    fun q(
        uris: List<Any>,
        type: FakeMessageType,
        message: String,
        attachment: JSONObject,
        forwardExtra: JSONObject?,
        writeType: FakeWriteType,
        shareOriginal: Boolean,
        highQuality: Boolean,
        onSuccess: kotlin.jvm.functions.Function1<Any?, Any?>,
        onFailure: kotlin.jvm.functions.Function1<Any?, Any?>,
    ) {
        sentUris += uris.map { uri -> uri.toString().removePrefix("uri:") }
        lastType = type
        lastWriteType = writeType
        check(message.isEmpty())
        check(attachment.optString("callingPkg") == "com.kakao.talk")
        check(forwardExtra == null)
        check(writeType == FakeWriteType.Connect)
        check(!shareOriginal)
        check(!highQuality)
        assertEquals("ok", onSuccess.invoke("ok"))
        assertEquals(null, onFailure.invoke("ignored"))
    }
}

private class RenamedThreadedInjectMediaSender(
    chatRoom: FakeChatRoom,
    threadId: Long?,
    sendWithThread: () -> Boolean,
    attachmentDecorator: (JSONObject) -> JSONObject?,
) {
    init {
        check(chatRoom.hashCode() != 0 || chatRoom.hashCode() == 0)
        check(threadId == null || threadId >= 0L)
        check(!sendWithThread())
        check(attachmentDecorator(JSONObject()) != null)
    }

    @Suppress("UNUSED_PARAMETER")
    fun z(
        sendingLog: Any,
        writeType: FakeWriteType,
        listener: FakeListener?,
    ) {
        check(sendingLog.hashCode() != Int.MIN_VALUE)
        check(writeType.name.isNotBlank())
        check(listener == null || listener.hashCode() != Int.MIN_VALUE)
    }
}

private class FakeThreadedRequestCompanion {
    @Suppress("UNUSED_PARAMETER")
    fun u(
        chatRoom: FakeChatRoomModel,
        sendingLog: Any,
        writeType: FakeWriteType,
        listener: FakeListener?,
        shouldRetry: Boolean,
    ) {
        check(chatRoom.roomId >= 0L)
        check(sendingLog.hashCode() != Int.MIN_VALUE)
        check(writeType.name.isNotBlank())
        check(listener == null || listener.hashCode() != Int.MIN_VALUE)
        check(!shouldRetry || shouldRetry)
    }
}

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

private class LegacyNameSensitiveChatRoom private constructor(
    val roomId: Long,
) {
    companion object {
        @JvmField
        val CompanionResolver = Resolver()
    }

    class Resolver {
        fun c(entity: FakeRoomEntity): LegacyNameSensitiveChatRoom {
            LegacyNameSensitiveRecorder.calls += "c"
            return LegacyNameSensitiveChatRoom(entity.roomId)
        }

        fun z(entity: FakeRoomEntity): LegacyNameSensitiveChatRoom {
            LegacyNameSensitiveRecorder.calls += "z"
            return LegacyNameSensitiveChatRoom(entity.roomId)
        }
    }
}

private object LegacyNameSensitiveRecorder {
    val calls = mutableListOf<String>()
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

class KakaoClassRegistryTest {
    @Test
    fun `registry constructed with fake classes exposes all fields`() {
        val registry = buildFakeRegistry()

        assertEquals(FakeMediaSender::class.java, registry.chatMediaSenderClass)
        assertEquals(FakeMessageType::class.java, registry.messageTypeClass)
        assertEquals(FakeChatRoomManager::class.java, registry.chatRoomManagerClass)
        assertEquals(FakeMediaItem::class.java, registry.mediaItemClass)
        assertNotNull(registry.singleSendMethod)
        assertNotNull(registry.multiSendMethod)
        assertNotNull(registry.mediaItemConstructor)
        assertNotNull(registry.photoType)
        assertEquals(FakeMessageType.Photo, registry.photoType)
        assertNotNull(registry.multiPhotoType)
        assertEquals(FakeMessageType.MultiPhoto, registry.multiPhotoType)
        assertNotNull(registry.writeTypeNone)
        assertEquals(FakeWriteType.None, registry.writeTypeNone)
    }

    @Test
    fun `method selector prefers concrete candidate over abstract one`() {
        val method =
            KakaoClassRegistry.selectMethodCandidateForTest(
                label = "roomDao",
                candidates =
                    listOf(
                        AbstractRoomDaoContainer::class.java.getMethod("O"),
                        ConcreteRoomDaoContainer::class.java.getMethod("O"),
                    ),
            )

        assertEquals(ConcreteRoomDaoContainer::class.java, method.declaringClass)
    }

    @Test
    fun `method selector rejects ambiguous concrete candidates`() {
        val error =
            assertFailsWith<IllegalStateException> {
                KakaoClassRegistry.selectMethodCandidateForTest(
                    label = "ambiguous",
                    candidates =
                        listOf(
                            AmbiguousMethodOwner::class.java.getMethod("a", Long::class.javaPrimitiveType),
                            AmbiguousMethodOwner::class.java.getMethod("b", Long::class.javaPrimitiveType),
                        ),
                )
            }

        assertTrue(error.message?.contains("ambiguous") == true)
    }

    @Test
    fun `method selector prefers known method name when candidates are ambiguous`() {
        val method =
            KakaoClassRegistry.selectMethodCandidateForTest(
                label = "direct resolver",
                candidates =
                    listOf(
                        AmbiguousMethodOwner::class.java.getMethod("a", Long::class.javaPrimitiveType),
                        AmbiguousMethodOwner::class.java.getMethod("b", Long::class.javaPrimitiveType),
                    ),
                preferredNames = setOf("b"),
            )

        assertEquals("b", method.name)
    }

    @Test
    fun `chat media sender selector accepts concrete subclass inheriting send methods`() {
        val selected =
            KakaoClassRegistry.selectChatMediaSenderCandidateForTest(
                candidates =
                    listOf(
                        AbstractInheritedMediaSender::class.java,
                        ConcreteInheritedMediaSender::class.java,
                    ),
                mediaItemClass = FakeMediaItem::class.java,
                function0Class = kotlin.jvm.functions.Function0::class.java,
                function1Class = kotlin.jvm.functions.Function1::class.java,
            )

        assertEquals(ConcreteInheritedMediaSender::class.java, selected)
    }

    @Test
    fun `chat media sender selector rejects ambiguous concrete classes`() {
        val error =
            assertFailsWith<IllegalStateException> {
                KakaoClassRegistry.selectChatMediaSenderCandidateForTest(
                    candidates =
                        listOf(
                            ConcreteInheritedMediaSender::class.java,
                            AlternateConcreteInheritedMediaSender::class.java,
                        ),
                    mediaItemClass = FakeMediaItem::class.java,
                    function0Class = kotlin.jvm.functions.Function0::class.java,
                    function1Class = kotlin.jvm.functions.Function1::class.java,
                )
            }

        assertTrue(error.message?.contains("ambiguous") == true)
    }

    @Test
    fun `chat media sender method resolver accepts inherited methods`() {
        val methods =
            KakaoClassRegistry.resolveChatMediaSenderMethodsForTest(
                chatMediaSenderClass = ConcreteInheritedMediaSender::class.java,
                mediaItemClass = FakeMediaItem::class.java,
                messageTypeClass = FakeMessageType::class.java,
            )

        assertEquals("n", methods.first.name)
        assertEquals("p", methods.second.name)
        assertEquals(AbstractInheritedMediaSender::class.java, methods.first.declaringClass)
        assertEquals(AbstractInheritedMediaSender::class.java, methods.second.declaringClass)
    }

    @Test
    fun `chat media sender method resolver accepts inherited non public methods`() {
        val methods =
            KakaoClassRegistry.resolveChatMediaSenderMethodsForTest(
                chatMediaSenderClass = ConcreteProtectedInheritedMediaSender::class.java,
                mediaItemClass = FakeMediaItem::class.java,
                messageTypeClass = FakeMessageType::class.java,
            )

        assertEquals("n", methods.first.name)
        assertEquals("p", methods.second.name)
        assertEquals(AbstractProtectedInheritedMediaSender::class.java, methods.first.declaringClass)
        assertEquals(AbstractProtectedInheritedMediaSender::class.java, methods.second.declaringClass)
    }
}

private abstract class AbstractRoomDaoContainer {
    @Suppress("FunctionName")
    abstract fun O(): FakeRoomDao
}

private class ConcreteRoomDaoContainer : AbstractRoomDaoContainer() {
    @Suppress("FunctionName")
    override fun O(): FakeRoomDao = FakeRoomDao()
}

private class AmbiguousMethodOwner {
    fun a(roomId: Long): FakeRoomEntity = FakeRoomEntity(roomId)

    fun b(roomId: Long): FakeRoomEntity = FakeRoomEntity(roomId)
}

private open class FakeBaseChatRoom

private class FakeDerivedChatRoom : FakeBaseChatRoom()

private class FakePolymorphicMediaSender(
    chatRoom: FakeBaseChatRoom,
    threadId: Long?,
    sendWithThread: () -> Boolean,
    attachmentDecorator: (JSONObject) -> JSONObject?,
) {
    private val roomClassName = chatRoom.javaClass.name

    companion object {
        val sentPaths = mutableListOf<String>()

        fun reset() {
            sentPaths.clear()
        }
    }

    init {
        check(roomClassName.isNotBlank())
        check(threadId == null)
        check(!sendWithThread())
        check(attachmentDecorator(JSONObject()) != null)
    }

    fun n(
        mediaItem: FakeMediaItem,
        suppressAnimation: Boolean,
    ) {
        check(!suppressAnimation)
        sentPaths += mediaItem.path
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
        error("not used in this test")
    }
}

private class ExactPreferredMediaSender {
    companion object {
        var exactCalls = 0
        var baseCalls = 0

        fun reset() {
            exactCalls = 0
            baseCalls = 0
        }
    }

    constructor(
        chatRoom: FakeBaseChatRoom,
        threadId: Long?,
        sendWithThread: () -> Boolean,
        attachmentDecorator: (JSONObject) -> JSONObject?,
    ) {
        check(chatRoom.javaClass.name.isNotBlank())
        check(threadId == null)
        check(!sendWithThread())
        check(attachmentDecorator(JSONObject()) != null)
        baseCalls += 1
    }

    constructor(
        chatRoom: FakeDerivedChatRoom,
        threadId: Long?,
        sendWithThread: () -> Boolean,
        attachmentDecorator: (JSONObject) -> JSONObject?,
    ) {
        check(chatRoom.javaClass.name.isNotBlank())
        check(threadId == null)
        check(!sendWithThread())
        check(attachmentDecorator(JSONObject()) != null)
        exactCalls += 1
    }

    fun n(
        mediaItem: FakeMediaItem,
        suppressAnimation: Boolean,
    ) {
        check(!suppressAnimation)
        check(mediaItem.path.isNotBlank())
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
        check(uris.isNotEmpty() || message == null)
        check(type.name.isNotBlank())
        check(attachment == null)
        check(forwardExtra == null)
        check(writeType.name.isNotBlank())
        check(!shareOriginal)
        check(!highQuality)
        check(listener == null)
    }
}

private abstract class AbstractInheritedMediaSender(
    chatRoom: FakeBaseChatRoom,
    threadId: Long?,
    sendWithThread: () -> Boolean,
    attachmentDecorator: (JSONObject) -> JSONObject?,
) {
    init {
        check(chatRoom.javaClass.name.isNotBlank())
        check(threadId == null)
        check(!sendWithThread())
        check(attachmentDecorator(JSONObject()) != null)
    }

    fun n(
        mediaItem: FakeMediaItem,
        suppressAnimation: Boolean,
    ) {
        check(!suppressAnimation)
        check(mediaItem.path.isNotBlank())
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
        check(uris.isNotEmpty() || message == null)
        check(type.name.isNotBlank())
        check(attachment == null)
        check(forwardExtra == null)
        check(writeType.name.isNotBlank())
        check(!shareOriginal)
        check(!highQuality)
        check(listener == null)
    }
}

private class ConcreteInheritedMediaSender(
    chatRoom: FakeBaseChatRoom,
    threadId: Long?,
    sendWithThread: () -> Boolean,
    attachmentDecorator: (JSONObject) -> JSONObject?,
) : AbstractInheritedMediaSender(chatRoom, threadId, sendWithThread, attachmentDecorator)

private class AlternateConcreteInheritedMediaSender(
    chatRoom: FakeBaseChatRoom,
    threadId: Long?,
    sendWithThread: () -> Boolean,
    attachmentDecorator: (JSONObject) -> JSONObject?,
) : AbstractInheritedMediaSender(chatRoom, threadId, sendWithThread, attachmentDecorator)

private abstract class AbstractProtectedInheritedMediaSender(
    chatRoom: FakeBaseChatRoom,
    threadId: Long?,
    sendWithThread: () -> Boolean,
    attachmentDecorator: (JSONObject) -> JSONObject?,
) {
    init {
        check(chatRoom.javaClass.name.isNotBlank())
        check(threadId == null)
        check(!sendWithThread())
        check(attachmentDecorator(JSONObject()) != null)
    }

    protected fun n(
        mediaItem: FakeMediaItem,
        suppressAnimation: Boolean,
    ) {
        check(!suppressAnimation)
        check(mediaItem.path.isNotBlank())
    }

    @Suppress("UNUSED_PARAMETER")
    protected fun p(
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
        check(uris.isNotEmpty() || message == null)
        check(type.name.isNotBlank())
        check(attachment == null)
        check(forwardExtra == null)
        check(writeType.name.isNotBlank())
        check(!shareOriginal)
        check(!highQuality)
        check(listener == null)
    }
}

private class ConcreteProtectedInheritedMediaSender(
    chatRoom: FakeBaseChatRoom,
    threadId: Long?,
    sendWithThread: () -> Boolean,
    attachmentDecorator: (JSONObject) -> JSONObject?,
) : AbstractProtectedInheritedMediaSender(chatRoom, threadId, sendWithThread, attachmentDecorator)

private fun buildFakeRegistry(): KakaoClassRegistry {
    val singleSend =
        FakeMediaSender::class.java.getMethod(
            "n",
            FakeMediaItem::class.java,
            Boolean::class.javaPrimitiveType,
        )
    val multiSend =
        FakeMediaSender::class.java.getMethod(
            "p",
            List::class.java,
            FakeMessageType::class.java,
            String::class.java,
            JSONObject::class.java,
            JSONObject::class.java,
            FakeWriteType::class.java,
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
            FakeListener::class.java,
        )
    val mediaItemCtor =
        FakeMediaItem::class.java.getConstructor(
            String::class.java,
            Long::class.javaPrimitiveType,
        )
    val masterDbField = FakeMasterDatabase::class.java.getDeclaredField("INSTANCE")
    val roomDaoMethod = FakeMasterDatabase::class.java.getMethod("O")
    val entityLookupMethod =
        FakeRoomDao::class.java.getMethod(
            "h",
            Long::class.javaPrimitiveType,
        )
    val broadResolver =
        FakeChatRoomManager::class.java.getMethod(
            "e0",
            Long::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
        )
    val directResolver =
        FakeChatRoomManager::class.java.getMethod(
            "d0",
            Long::class.javaPrimitiveType,
        )
    return KakaoClassRegistry(
        mediaItemClass = FakeMediaItem::class.java,
        function0Class = kotlin.jvm.functions.Function0::class.java,
        function1Class = kotlin.jvm.functions.Function1::class.java,
        masterDatabaseClass = FakeMasterDatabase::class.java,
        writeTypeClass = FakeWriteType::class.java,
        listenerClass = FakeListener::class.java,
        chatMediaSenderClass = FakeMediaSender::class.java,
        messageTypeClass = FakeMessageType::class.java,
        chatRoomManagerClass = FakeChatRoomManager::class.java,
        chatRoomClass = FakeChatRoomModel::class.java,
        singleSendMethod = singleSend,
        multiSendMethod = multiSend,
        mediaItemConstructor = mediaItemCtor,
        masterDbSingletonField = masterDbField,
        roomDaoMethod = roomDaoMethod,
        entityLookupMethod = entityLookupMethod,
        broadRoomResolverMethod = broadResolver,
        directRoomResolverMethod = directResolver,
        photoType = FakeMessageType.Photo,
        multiPhotoType = FakeMessageType.MultiPhoto,
        writeTypeNone = FakeWriteType.None,
    )
}

private fun buildPolymorphicRegistry(): KakaoClassRegistry {
    val singleSend =
        FakePolymorphicMediaSender::class.java.getMethod(
            "n",
            FakeMediaItem::class.java,
            Boolean::class.javaPrimitiveType,
        )
    val multiSend =
        FakePolymorphicMediaSender::class.java.getMethod(
            "p",
            List::class.java,
            FakeMessageType::class.java,
            String::class.java,
            JSONObject::class.java,
            JSONObject::class.java,
            FakeWriteType::class.java,
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
            FakeListener::class.java,
        )
    val mediaItemCtor =
        FakeMediaItem::class.java.getConstructor(
            String::class.java,
            Long::class.javaPrimitiveType,
        )
    val masterDbField = FakeMasterDatabase::class.java.getDeclaredField("INSTANCE")
    val roomDaoMethod = FakeMasterDatabase::class.java.getMethod("O")
    val entityLookupMethod =
        FakeRoomDao::class.java.getMethod(
            "h",
            Long::class.javaPrimitiveType,
        )
    val broadResolver =
        FakeChatRoomManager::class.java.getMethod(
            "e0",
            Long::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
        )
    val directResolver =
        FakeChatRoomManager::class.java.getMethod(
            "d0",
            Long::class.javaPrimitiveType,
        )
    return KakaoClassRegistry(
        mediaItemClass = FakeMediaItem::class.java,
        function0Class = kotlin.jvm.functions.Function0::class.java,
        function1Class = kotlin.jvm.functions.Function1::class.java,
        masterDatabaseClass = FakeMasterDatabase::class.java,
        writeTypeClass = FakeWriteType::class.java,
        listenerClass = FakeListener::class.java,
        chatMediaSenderClass = FakePolymorphicMediaSender::class.java,
        messageTypeClass = FakeMessageType::class.java,
        chatRoomManagerClass = FakeChatRoomManager::class.java,
        chatRoomClass = FakeBaseChatRoom::class.java,
        singleSendMethod = singleSend,
        multiSendMethod = multiSend,
        mediaItemConstructor = mediaItemCtor,
        masterDbSingletonField = masterDbField,
        roomDaoMethod = roomDaoMethod,
        entityLookupMethod = entityLookupMethod,
        broadRoomResolverMethod = broadResolver,
        directRoomResolverMethod = directResolver,
        photoType = FakeMessageType.Photo,
        multiPhotoType = FakeMessageType.MultiPhoto,
        writeTypeNone = FakeWriteType.None,
    )
}

private fun buildExactPreferredRegistry(): KakaoClassRegistry {
    val singleSend =
        ExactPreferredMediaSender::class.java.getMethod(
            "n",
            FakeMediaItem::class.java,
            Boolean::class.javaPrimitiveType,
        )
    val multiSend =
        ExactPreferredMediaSender::class.java.getMethod(
            "p",
            List::class.java,
            FakeMessageType::class.java,
            String::class.java,
            JSONObject::class.java,
            JSONObject::class.java,
            FakeWriteType::class.java,
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
            FakeListener::class.java,
        )
    val mediaItemCtor =
        FakeMediaItem::class.java.getConstructor(
            String::class.java,
            Long::class.javaPrimitiveType,
        )
    val masterDbField = FakeMasterDatabase::class.java.getDeclaredField("INSTANCE")
    val roomDaoMethod = FakeMasterDatabase::class.java.getMethod("O")
    val entityLookupMethod =
        FakeRoomDao::class.java.getMethod(
            "h",
            Long::class.javaPrimitiveType,
        )
    val broadResolver =
        FakeChatRoomManager::class.java.getMethod(
            "e0",
            Long::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
        )
    val directResolver =
        FakeChatRoomManager::class.java.getMethod(
            "d0",
            Long::class.javaPrimitiveType,
        )
    return KakaoClassRegistry(
        mediaItemClass = FakeMediaItem::class.java,
        function0Class = kotlin.jvm.functions.Function0::class.java,
        function1Class = kotlin.jvm.functions.Function1::class.java,
        masterDatabaseClass = FakeMasterDatabase::class.java,
        writeTypeClass = FakeWriteType::class.java,
        listenerClass = FakeListener::class.java,
        chatMediaSenderClass = ExactPreferredMediaSender::class.java,
        messageTypeClass = FakeMessageType::class.java,
        chatRoomManagerClass = FakeChatRoomManager::class.java,
        chatRoomClass = FakeBaseChatRoom::class.java,
        singleSendMethod = singleSend,
        multiSendMethod = multiSend,
        mediaItemConstructor = mediaItemCtor,
        masterDbSingletonField = masterDbField,
        roomDaoMethod = roomDaoMethod,
        entityLookupMethod = entityLookupMethod,
        broadRoomResolverMethod = broadResolver,
        directRoomResolverMethod = directResolver,
        photoType = FakeMessageType.Photo,
        multiPhotoType = FakeMessageType.MultiPhoto,
        writeTypeNone = FakeWriteType.None,
    )
}

private fun buildLegacyNameSensitiveRegistry(): KakaoClassRegistry {
    val singleSend =
        FakeMediaSender::class.java.getMethod(
            "n",
            FakeMediaItem::class.java,
            Boolean::class.javaPrimitiveType,
        )
    val multiSend =
        FakeMediaSender::class.java.getMethod(
            "p",
            List::class.java,
            FakeMessageType::class.java,
            String::class.java,
            JSONObject::class.java,
            JSONObject::class.java,
            FakeWriteType::class.java,
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
            FakeListener::class.java,
        )
    val mediaItemCtor =
        FakeMediaItem::class.java.getConstructor(
            String::class.java,
            Long::class.javaPrimitiveType,
        )
    val masterDbField = FakeMasterDatabase::class.java.getDeclaredField("INSTANCE")
    val roomDaoMethod = FakeMasterDatabase::class.java.getMethod("O")
    val entityLookupMethod =
        FakeRoomDao::class.java.getMethod(
            "h",
            Long::class.javaPrimitiveType,
        )
    val broadResolver =
        FakeChatRoomManager::class.java.getMethod(
            "e0",
            Long::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
        )
    val directResolver =
        FakeChatRoomManager::class.java.getMethod(
            "d0",
            Long::class.javaPrimitiveType,
        )
    return KakaoClassRegistry(
        mediaItemClass = FakeMediaItem::class.java,
        function0Class = kotlin.jvm.functions.Function0::class.java,
        function1Class = kotlin.jvm.functions.Function1::class.java,
        masterDatabaseClass = FakeMasterDatabase::class.java,
        writeTypeClass = FakeWriteType::class.java,
        listenerClass = FakeListener::class.java,
        chatMediaSenderClass = FakeMediaSender::class.java,
        messageTypeClass = FakeMessageType::class.java,
        chatRoomManagerClass = FakeChatRoomManager::class.java,
        chatRoomClass = LegacyNameSensitiveChatRoom::class.java,
        singleSendMethod = singleSend,
        multiSendMethod = multiSend,
        mediaItemConstructor = mediaItemCtor,
        masterDbSingletonField = masterDbField,
        roomDaoMethod = roomDaoMethod,
        entityLookupMethod = entityLookupMethod,
        broadRoomResolverMethod = broadResolver,
        directRoomResolverMethod = directResolver,
        photoType = FakeMessageType.Photo,
        multiPhotoType = FakeMessageType.MultiPhoto,
        writeTypeNone = FakeWriteType.None,
    )
}

private fun buildRenamedThreadedRegistry(): KakaoClassRegistry {
    val singleSend =
        RenamedThreadedEntryMediaSender::class.java.getMethod(
            "n",
            FakeMediaItem::class.java,
            Boolean::class.javaPrimitiveType,
        )
    val multiSend =
        RenamedThreadedEntryMediaSender::class.java.getMethod(
            "p",
            List::class.java,
            FakeMessageType::class.java,
            String::class.java,
            JSONObject::class.java,
            JSONObject::class.java,
            FakeWriteType::class.java,
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
            FakeListener::class.java,
        )
    val mediaItemCtor =
        FakeMediaItem::class.java.getConstructor(
            String::class.java,
            Long::class.javaPrimitiveType,
        )
    val masterDbField = FakeMasterDatabase::class.java.getDeclaredField("INSTANCE")
    val roomDaoMethod = FakeMasterDatabase::class.java.getMethod("O")
    val entityLookupMethod =
        FakeRoomDao::class.java.getMethod(
            "h",
            Long::class.javaPrimitiveType,
        )
    val broadResolver =
        FakeChatRoomManager::class.java.getMethod(
            "e0",
            Long::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
        )
    val directResolver =
        FakeChatRoomManager::class.java.getMethod(
            "d0",
            Long::class.javaPrimitiveType,
        )
    return KakaoClassRegistry(
        mediaItemClass = FakeMediaItem::class.java,
        function0Class = kotlin.jvm.functions.Function0::class.java,
        function1Class = kotlin.jvm.functions.Function1::class.java,
        masterDatabaseClass = FakeMasterDatabase::class.java,
        writeTypeClass = FakeWriteType::class.java,
        listenerClass = FakeListener::class.java,
        chatMediaSenderClass = RenamedThreadedEntryMediaSender::class.java,
        messageTypeClass = FakeMessageType::class.java,
        chatRoomManagerClass = FakeChatRoomManager::class.java,
        chatRoomClass = FakeChatRoomModel::class.java,
        singleSendMethod = singleSend,
        multiSendMethod = multiSend,
        mediaItemConstructor = mediaItemCtor,
        masterDbSingletonField = masterDbField,
        roomDaoMethod = roomDaoMethod,
        entityLookupMethod = entityLookupMethod,
        broadRoomResolverMethod = broadResolver,
        directRoomResolverMethod = directResolver,
        photoType = FakeMessageType.Photo,
        multiPhotoType = FakeMessageType.MultiPhoto,
        writeTypeNone = FakeWriteType.None,
    )
}
