package party.qwer.iris

data class IrisRuntimePaths(
    val dataDir: String,
    val configPath: String,
    val logDir: String,
)

object IrisRuntimePathPolicy {
    private const val DEFAULT_DATA_DIR = "/data/iris"
    private const val DEFAULT_CONFIG_FILENAME = "config.json"
    private const val DEFAULT_LOG_DIR = "logs"

    fun resolve(env: Map<String, String> = System.getenv()): IrisRuntimePaths {
        val dataDir = env.nonBlank("IRIS_DATA_DIR") ?: DEFAULT_DATA_DIR
        val configPath = env.nonBlank("IRIS_CONFIG_PATH") ?: "$dataDir/$DEFAULT_CONFIG_FILENAME"
        val logDir = env.nonBlank("IRIS_LOG_DIR") ?: "$dataDir/$DEFAULT_LOG_DIR"
        return IrisRuntimePaths(
            dataDir = dataDir,
            configPath = configPath,
            logDir = logDir,
        )
    }
}

private fun Map<String, String>.nonBlank(key: String): String? = this[key]?.trim()?.takeIf { it.isNotEmpty() }
