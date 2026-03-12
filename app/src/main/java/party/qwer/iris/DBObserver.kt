package party.qwer.iris

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class DBObserver(
    private val observerHelper: ObserverHelper,
) {
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var pollingJob: Job? = null

    @Synchronized
    fun startPolling() {
        if (pollingJob?.isActive == true) {
            IrisLogger.debug("DB Polling thread is already running.")
            return
        }

        pollingJob =
            coroutineScope.launch {
                IrisLogger.info("DB Polling thread started.")
                while (isActive) {
                    try {
                        observerHelper.checkChange()
                    } catch (e: Exception) {
                        IrisLogger.error("Error during DB polling: ${e.message}", e)
                    }

                    delay(Configurable.dbPollingRate.takeIf { it > 0 } ?: 1000)
                }
            }
    }

    @Synchronized
    fun stopPolling() {
        val job = pollingJob ?: return
        pollingJob = null

        runBlocking {
            job.cancelAndJoin()
        }
        IrisLogger.info("DB Polling thread stopped.")
    }
}
