package party.qwer.iris

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

private val bridgeRuntimeConfigJson =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

@Serializable
private data class BridgeRuntimeConfigSnapshot(
    val bridgeToken: String = "",
)

enum class BridgeTokenSource {
    CONFIG_FILE,
    ENV_FALLBACK,
    NONE,
}

data class BridgeTokenResolution(
    val token: String,
    val source: BridgeTokenSource,
    val configPath: String,
)

object BridgeBootstrapConfigResolver {
    fun resolve(
        env: Map<String, String> = System.getenv(),
        fileReader: (String) -> String? = ::readBridgeRuntimeConfigFile,
    ): BridgeTokenResolution {
        val configPath = IrisRuntimePathPolicy.resolve(env).configPath
        val envToken =
            env["IRIS_BRIDGE_TOKEN"]
                ?.trim()
                .orEmpty()
        val configToken =
            runCatching {
                fileReader(configPath)
            }.getOrNull()
                ?.let(::decodeBridgeToken)
                .orEmpty()
        return when {
            configToken.isNotBlank() ->
                BridgeTokenResolution(
                    token = configToken,
                    source = BridgeTokenSource.CONFIG_FILE,
                    configPath = configPath,
                )

            envToken.isNotBlank() ->
                BridgeTokenResolution(
                    token = envToken,
                    source = BridgeTokenSource.ENV_FALLBACK,
                    configPath = configPath,
                )

            else ->
                BridgeTokenResolution(
                    token = "",
                    source = BridgeTokenSource.NONE,
                    configPath = configPath,
                )
        }
    }
}

fun resolveBridgeToken(
    env: Map<String, String> = System.getenv(),
    fileReader: (String) -> String? = ::readBridgeRuntimeConfigFile,
): String = BridgeBootstrapConfigResolver.resolve(env = env, fileReader = fileReader).token

internal fun decodeBridgeToken(rawConfig: String): String =
    runCatching {
        bridgeRuntimeConfigJson.decodeFromString<BridgeRuntimeConfigSnapshot>(rawConfig)
    }.getOrNull()
        ?.bridgeToken
        ?.trim()
        .orEmpty()

private fun readBridgeRuntimeConfigFile(path: String): String? =
    runCatching {
        File(path)
            .takeIf { it.isFile }
            ?.readText()
    }.getOrNull()
