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
import java.io.File
import kotlin.concurrent.Volatile

class ImageDeleter(
    private val imageDirPath: String,
    private val deletionInterval: Long,
    private val retentionMillis: Long,
) {
    private val scopeJob = SupervisorJob()
    private val coroutineScope = CoroutineScope(scopeJob + Dispatchers.IO)

    @Volatile
    private var running = true

    @Volatile
    private var deletionJob: Job? = null

    @Synchronized
    fun startDeletion() {
        if (!running) {
            IrisLogger.debug("Image deletion already stopped; refusing restart.")
            return
        }
        if (deletionJob?.isActive == true) {
            return
        }

        deletionJob =
            coroutineScope.launch {
                while (isActive && running) {
                    deleteOldImages()
                    delay(deletionInterval)
                }
            }
    }

    @Synchronized
    fun stopDeletion() {
        if (!running) {
            return
        }
        running = false
        runBlocking {
            deletionJob?.cancelAndJoin()
            scopeJob.cancelAndJoin()
        }
        deletionJob = null
    }

    private fun deleteOldImages() {
        val imageDir = File(imageDirPath)
        if (!imageDir.exists() || !imageDir.isDirectory) {
            IrisLogger.debug("Image directory does not exist: $imageDirPath")
            return
        }

        val expirationCutoff = System.currentTimeMillis() - retentionMillis
        imageDir.listFiles()?.forEach { file ->
            if (file.isFile && file.lastModified() < expirationCutoff) {
                if (file.delete()) {
                    IrisLogger.debug("Deleted old image file: " + file.name)
                } else {
                    IrisLogger.error("Failed to delete image file: " + file.name)
                }
            }
        }
    }
}
