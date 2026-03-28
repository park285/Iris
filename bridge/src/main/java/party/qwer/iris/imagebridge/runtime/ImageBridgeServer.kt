package party.qwer.iris.imagebridge.runtime

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.util.Log
import party.qwer.iris.ImageBridgeProtocol
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

internal object ImageBridgeServer {
    private const val TAG = "IrisBridge"
    private const val INITIAL_RESTART_DELAY_MS = 1_000L
    private const val MAX_RESTART_DELAY_MS = 30_000L
    private const val CLIENT_EXECUTOR_CORE_THREADS = 2
    private const val CLIENT_EXECUTOR_MAX_THREADS = 8
    private const val CLIENT_EXECUTOR_QUEUE_CAPACITY = 64
    private const val CLIENT_EXECUTOR_KEEP_ALIVE_MS = 60_000L
    private const val CLIENT_READ_TIMEOUT_MS = 5_000

    private val running = AtomicBoolean(false)
    private val restartCount = AtomicInteger(0)
    private val lastCrashMessage = AtomicReference<String?>(null)
    private val specStatus = AtomicReference<BridgeSpecStatus?>(null)
    private val peerIdentityValidator = BridgePeerIdentityValidator()

    @Volatile
    private var requestHandler: ImageBridgeRequestHandler? = null

    @Volatile
    private var clientExecutor: ExecutorService? = null

    fun start(
        context: Context,
        registry: KakaoClassRegistry?,
        registryError: String? = null,
    ) {
        if (!running.compareAndSet(false, true)) {
            Log.w(TAG, "bridge server already running")
            return
        }
        restartCount.set(0)
        lastCrashMessage.set(null)
        val imageSender = registry?.let { KakaoImageSender(it) }
        val verifier =
            BridgeHookSpecVerifier(
                registry = registry,
                registryError = registryError,
            )
        val initialSpecStatus = verifier.verify()
        specStatus.set(initialSpecStatus)
        if (!initialSpecStatus.ready) {
            Log.e(TAG, "bridge hook spec verification failed: ${initialSpecStatus.checks.filterNot { it.ok }.joinToString { it.name }}")
        }
        requestHandler =
            ImageBridgeRequestHandler(
                imageSender = { request ->
                    val sender =
                        imageSender
                            ?: error("KakaoClassRegistry not available: ${registryError ?: "unknown error"}")
                    sender.send(request)
                },
                healthProvider = ::healthSnapshot,
            )
        clientExecutor = newClientExecutor()
        Thread(
            {
                runServerLoop()
            },
            "iris-bridge-server",
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun runServerLoop() {
        var consecutiveFailures = 0
        try {
            while (running.get()) {
                try {
                    serve()
                    if (running.get()) {
                        val delayMs = nextBridgeRestartDelayMs(++consecutiveFailures)
                        restartCount.incrementAndGet()
                        lastCrashMessage.set("server loop exited unexpectedly")
                        Log.e(TAG, "bridge server stopped unexpectedly; restarting in ${delayMs}ms")
                        sleepBeforeRestart(delayMs)
                    }
                } catch (e: Exception) {
                    if (!running.get()) {
                        break
                    }
                    consecutiveFailures += 1
                    restartCount.incrementAndGet()
                    lastCrashMessage.set(e.message ?: e.javaClass.name)
                    val delayMs = nextBridgeRestartDelayMs(consecutiveFailures)
                    Log.e(TAG, "bridge server crashed; restarting in ${delayMs}ms", e)
                    sleepBeforeRestart(delayMs)
                }
            }
        } finally {
            clientExecutor?.shutdown()
            clientExecutor = null
        }
    }

    private fun serve() {
        val serverSocket = LocalServerSocket(ImageBridgeProtocol.SOCKET_NAME)
        try {
            Log.i(TAG, "bridge server listening on @${ImageBridgeProtocol.SOCKET_NAME}")
            while (running.get()) {
                val client = serverSocket.accept()
                dispatchClient(client)
            }
        } finally {
            runCatching { serverSocket.close() }
            Log.i(TAG, "bridge server socket closed")
        }
    }

    private fun dispatchClient(client: LocalSocket) {
        val executor = clientExecutor
        val handler = requestHandler
        if (executor == null || handler == null) {
            runCatching {
                writeFrame(client.outputStream, failureResponse("sender not initialized"))
            }
            runCatching { client.close() }
            return
        }
        val peerUid =
            runCatching {
                client.peerCredentials.uid
            }.getOrNull()
        try {
            peerIdentityValidator.validate(peerUid)
        } catch (error: IllegalArgumentException) {
            Log.e(TAG, "unauthorized bridge client uid=$peerUid")
            runCatching {
                writeFrame(client.outputStream, failureResponse(error.message ?: "unauthorized bridge client"))
            }
            runCatching { client.close() }
            return
        }
        try {
            configureClientReadTimeout(client)
            executor.execute {
                handleClient(client, handler)
            }
        } catch (e: RejectedExecutionException) {
            Log.e(TAG, "client dispatch rejected", e)
            runCatching {
                writeFrame(client.outputStream, failureResponse("bridge shutting down"))
            }
            runCatching { client.close() }
        }
    }

    private fun handleClient(
        client: LocalSocket,
        handler: ImageBridgeRequestHandler,
    ) {
        try {
            val request = ImageBridgeProtocol.readRequestFrame(client.inputStream)
            val response = handler.handle(request)
            ImageBridgeProtocol.writeFrame(client.outputStream, response)
        } catch (e: Exception) {
            Log.e(TAG, "client handler error", e)
            runCatching {
                writeFrame(client.outputStream, failureResponse(e.message ?: "internal error"))
            }
        } finally {
            runCatching { client.close() }
        }
    }

    private fun writeFrame(
        output: java.io.OutputStream,
        response: ImageBridgeProtocol.ImageBridgeResponse,
    ) = ImageBridgeProtocol.writeFrame(output, response)

    private fun failureResponse(error: String) = ImageBridgeRequestHandler.failureResponse(error)

    private fun healthSnapshot(): ImageBridgeHealthSnapshot =
        ImageBridgeHealthSnapshot(
            running = running.get(),
            specStatus = specStatus.get() ?: BridgeSpecStatus(ready = false, checkedAtEpochMs = 0L, checks = emptyList()),
            discoverySnapshot = BridgeDiscovery.snapshot(),
            restartCount = restartCount.get(),
            lastCrashMessage = lastCrashMessage.get(),
        )

    private fun sleepBeforeRestart(delayMs: Long) {
        runCatching {
            Thread.sleep(delayMs)
        }.onFailure { error ->
            if (error is InterruptedException) {
                Thread.currentThread().interrupt()
                running.set(false)
            }
        }
    }

    internal fun nextBridgeRestartDelayMs(failureCount: Int): Long {
        val exponent = (failureCount - 1).coerceAtLeast(0).coerceAtMost(5)
        return (INITIAL_RESTART_DELAY_MS shl exponent).coerceAtMost(MAX_RESTART_DELAY_MS)
    }

    internal fun newClientExecutorForTest(): ThreadPoolExecutor = newClientExecutor()

    private fun newClientExecutor(): ThreadPoolExecutor =
        ThreadPoolExecutor(
            CLIENT_EXECUTOR_CORE_THREADS,
            CLIENT_EXECUTOR_MAX_THREADS,
            CLIENT_EXECUTOR_KEEP_ALIVE_MS,
            TimeUnit.MILLISECONDS,
            LinkedBlockingQueue(CLIENT_EXECUTOR_QUEUE_CAPACITY),
            { runnable ->
                Thread(runnable, "iris-bridge-client").apply {
                    isDaemon = true
                }
            },
            ThreadPoolExecutor.AbortPolicy(),
        ).apply {
            allowCoreThreadTimeOut(true)
        }

    private fun configureClientReadTimeout(client: LocalSocket) {
        runCatching {
            client.javaClass
                .getMethod("setSoTimeout", Int::class.javaPrimitiveType)
                .invoke(client, CLIENT_READ_TIMEOUT_MS)
        }.onFailure { error ->
            Log.w(TAG, "failed to set bridge client read timeout: ${error.message}")
        }
    }
}
