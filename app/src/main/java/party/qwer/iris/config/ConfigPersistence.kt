package party.qwer.iris.config

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import party.qwer.iris.IrisLogger
import party.qwer.iris.UserConfigState
import party.qwer.iris.decodeConfigValues
import party.qwer.iris.model.UserConfigValues
import party.qwer.iris.toPersistedConfigValues
import party.qwer.iris.toUserConfigState
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal data class LoadedConfig(
    val userState: UserConfigState,
    val migratedLegacyEndpoint: Boolean,
    val migratedLegacySecrets: Boolean,
) {
    val migratedLegacyConfig: Boolean
        get() = migratedLegacyEndpoint || migratedLegacySecrets
}

internal class ConfigPersistence(
    private val configPath: String,
    private val json: Json,
) {
    fun load(): LoadedConfig? {
        val configFile = File(configPath)
        if (!configFile.exists()) {
            return null
        }

        try {
            val jsonString = configFile.readText()
            val decoded = decodeConfigValues(json, jsonString)
            val userState = decoded.values.toUserConfigState()
            val validationErrors = ConfigPolicy.validate(userState)
            if (validationErrors.isNotEmpty()) {
                IrisLogger.error(
                    "Config validation failed for $configPath: " +
                        validationErrors.joinToString { "${it.field}: ${it.message}" },
                )
                backupBrokenConfig()
                return null
            }
            return LoadedConfig(
                userState = userState,
                migratedLegacyEndpoint = decoded.migratedLegacyEndpoint,
                migratedLegacySecrets = decoded.migratedLegacySecrets,
            )
        } catch (e: IOException) {
            IrisLogger.error("Error reading config.json from $configPath, using in-memory defaults: ${e.message}")
            backupBrokenConfig()
        } catch (e: SerializationException) {
            IrisLogger.error("JSON parsing error in config.json from $configPath, using in-memory defaults: ${e.message}")
            backupBrokenConfig()
        }
        return null
    }

    fun save(state: UserConfigState): Boolean {
        val configFile = File(configPath)
        val parentDir = configFile.parentFile
        if (parentDir != null && !parentDir.exists() && !parentDir.mkdirs()) {
            IrisLogger.error("Failed to create config directory: ${parentDir.absolutePath}")
            return false
        }

        val tempFile = File("${configFile.absolutePath}.tmp")
        try {
            val jsonString =
                json.encodeToString(
                    UserConfigValues.serializer(),
                    state.toPersistedConfigValues(),
                )
            FileOutputStream(tempFile).use { output ->
                output.write(jsonString.toByteArray())
                output.fd.sync()
            }
            moveConfigAtomically(tempFile, configFile)
            return true
        } catch (e: IOException) {
            IrisLogger.error("Error writing config to file $configPath: ${e.message}")
        } catch (e: SerializationException) {
            IrisLogger.error("JSON error while saving config to $configPath: ${e.message}")
        }
        tempFile.delete()
        return false
    }

    fun backupBrokenConfig() {
        val configFile = File(configPath)
        if (!configFile.exists()) {
            return
        }
        val backupFile = File("${configFile.absolutePath}.bak")
        runCatching {
            Files.move(
                configFile.toPath(),
                backupFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
            IrisLogger.info("Backed up unreadable config to ${backupFile.absolutePath}")
        }.onFailure { error ->
            IrisLogger.error("Failed to back up unreadable config: ${error.message}")
        }
    }

    @Throws(IOException::class)
    private fun moveConfigAtomically(
        tempFile: File,
        targetFile: File,
    ) {
        try {
            Files.move(
                tempFile.toPath(),
                targetFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                tempFile.toPath(),
                targetFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }
}
