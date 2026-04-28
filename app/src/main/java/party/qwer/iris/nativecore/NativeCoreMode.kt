package party.qwer.iris.nativecore

internal enum class NativeCoreMode {
    OFF,
    SHADOW,
    ON,
}

internal data class NativeCoreModeConfig(
    val mode: NativeCoreMode,
    val libraryPath: String,
    val parseWarning: String? = null,
) {
    val requiresLoad: Boolean
        get() = mode != NativeCoreMode.OFF

    companion object {
        private const val DEFAULT_LIBRARY_PATH = "/data/iris/lib/libiris_native_core.so"

        fun fromEnv(env: Map<String, String> = System.getenv()): NativeCoreModeConfig {
            val rawMode = env["IRIS_NATIVE_CORE"]?.trim()
            val mode = parseMode(rawMode)
            val warning =
                if (!rawMode.isNullOrBlank() && mode == null) {
                    "unsupported IRIS_NATIVE_CORE value: $rawMode"
                } else {
                    null
                }
            val path = env["IRIS_NATIVE_LIB_PATH"]?.trim().orEmpty().ifBlank { DEFAULT_LIBRARY_PATH }
            return NativeCoreModeConfig(
                mode = mode ?: NativeCoreMode.OFF,
                libraryPath = path,
                parseWarning = warning,
            )
        }

        private fun parseMode(rawMode: String?): NativeCoreMode? =
            when (rawMode?.trim()?.lowercase()) {
                null,
                "",
                "off",
                -> NativeCoreMode.OFF

                "shadow" -> NativeCoreMode.SHADOW
                "on" -> NativeCoreMode.ON
                else -> null
            }
    }
}
