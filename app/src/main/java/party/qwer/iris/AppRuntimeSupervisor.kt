package party.qwer.iris

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking

internal class AppRuntimeSupervisor(
    private val runtime: AppRuntime,
    private val awaitStopSignal: suspend () -> Unit = { awaitCancellation() },
    private val addShutdownHook: (Thread) -> Unit = { hook -> Runtime.getRuntime().addShutdownHook(hook) },
    private val removeShutdownHook: (Thread) -> Unit = { hook ->
        runCatching {
            Runtime.getRuntime().removeShutdownHook(hook)
        }
    },
) {
    suspend fun runUntilStopped() {
        runtime.startSuspend()
        val shutdownHook =
            Thread {
                runBlocking {
                    runtime.stop()
                }
            }
        addShutdownHook(shutdownHook)
        try {
            awaitStopSignal()
        } finally {
            removeShutdownHook(shutdownHook)
            runtime.stop()
        }
    }
}
