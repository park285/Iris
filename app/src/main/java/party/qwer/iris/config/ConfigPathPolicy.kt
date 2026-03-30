package party.qwer.iris.config

internal object ConfigPathPolicy {
    private const val DEFAULT_DATA_DIR = "/data/iris"
    private const val DEFAULT_CONFIG_FILENAME = "config.json"
    private const val DEFAULT_LOG_DIR = "logs"

    fun resolveConfigPath(
        envConfigPath: String? = System.getenv("IRIS_CONFIG_PATH"),
    ): String =
        envConfigPath?.trim()?.takeIf { it.isNotEmpty() }
            ?: "$DEFAULT_DATA_DIR/$DEFAULT_CONFIG_FILENAME"

    fun resolveLogDirectory(
        envLogDir: String? = System.getenv("IRIS_LOG_DIR"),
    ): String =
        envLogDir?.trim()?.takeIf { it.isNotEmpty() }
            ?: "$DEFAULT_DATA_DIR/$DEFAULT_LOG_DIR"
}
