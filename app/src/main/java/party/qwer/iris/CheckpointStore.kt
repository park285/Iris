package party.qwer.iris

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

interface CheckpointStore {
    fun load(streamName: String): Long?

    fun save(
        streamName: String,
        lastLogId: Long,
    )
}

internal class FileCheckpointStore(
    private val file: File = File("${PathUtils.getAppPath()}databases/iris-checkpoints.json"),
) : CheckpointStore {
    private val json = Json { prettyPrint = false }

    @Synchronized
    override fun load(streamName: String): Long? {
        if (!file.exists()) {
            return null
        }
        val root = readRoot() ?: return null
        return root[streamName]?.jsonPrimitive?.longOrNull
    }

    @Synchronized
    override fun save(
        streamName: String,
        lastLogId: Long,
    ) {
        val parentDir = file.parentFile
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs()
        }
        val updatedMap = readRoot().orEmpty().toMutableMap()
        updatedMap[streamName] = JsonPrimitive(lastLogId)
        val tempFile = File("${file.absolutePath}.tmp")
        tempFile.writeText(JsonObject(updatedMap).toString())
        try {
            Files.move(
                tempFile.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(
                tempFile.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private fun readRoot(): Map<String, JsonPrimitive>? =
        runCatching {
            json.parseToJsonElement(file.readText()).jsonObject.mapValues { (_, value) ->
                value.jsonPrimitive
            }
        }.getOrNull()
}
