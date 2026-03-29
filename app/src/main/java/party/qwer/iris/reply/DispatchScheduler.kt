package party.qwer.iris.reply

import party.qwer.iris.GlobalDispatchGate

internal class DispatchScheduler(
    baseIntervalMs: () -> Long,
    jitterMaxMs: () -> Long,
    clock: () -> Long = System::currentTimeMillis,
) {
    private val gate =
        GlobalDispatchGate(
            baseIntervalMs = baseIntervalMs,
            jitterMaxMs = jitterMaxMs,
            clock = clock,
        )

    suspend fun awaitPermit() {
        gate.awaitPermit()
    }
}
