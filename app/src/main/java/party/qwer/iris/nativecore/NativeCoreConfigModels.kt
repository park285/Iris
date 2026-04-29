package party.qwer.iris.nativecore

internal data class NativeCoreComponentModes(
    val decrypt: NativeCoreComponentMode = NativeCoreComponentMode.INHERIT,
    val routing: NativeCoreComponentMode = NativeCoreComponentMode.OFF,
    val parsers: NativeCoreComponentMode = NativeCoreComponentMode.OFF,
    val projections: NativeCoreComponentMode = NativeCoreComponentMode.OFF,
    val webhookPayload: NativeCoreComponentMode = NativeCoreComponentMode.OFF,
) {
    fun configuredMode(component: NativeCoreComponent): NativeCoreComponentMode =
        when (component) {
            NativeCoreComponent.DECRYPT -> decrypt
            NativeCoreComponent.ROUTING -> routing
            NativeCoreComponent.PARSERS -> parsers
            NativeCoreComponent.PROJECTIONS -> projections
            NativeCoreComponent.WEBHOOK_PAYLOAD -> webhookPayload
        }
}

internal data class NativeCoreStrictModes(
    val global: Boolean = false,
    val decrypt: Boolean? = null,
    val routing: Boolean? = null,
    val parsers: Boolean? = null,
    val projections: Boolean? = null,
    val webhookPayload: Boolean? = null,
) {
    fun isStrict(
        component: NativeCoreComponent,
        effectiveMode: NativeCoreMode,
    ): Boolean {
        if (effectiveMode != NativeCoreMode.ON) return false
        return configuredStrict(component) ?: global
    }

    private fun configuredStrict(component: NativeCoreComponent): Boolean? =
        when (component) {
            NativeCoreComponent.DECRYPT -> decrypt
            NativeCoreComponent.ROUTING -> routing
            NativeCoreComponent.PARSERS -> parsers
            NativeCoreComponent.PROJECTIONS -> projections
            NativeCoreComponent.WEBHOOK_PAYLOAD -> webhookPayload
        }
}

internal data class NativeCoreModeConfig(
    val mode: NativeCoreMode,
    val libraryPath: String,
    val componentModes: NativeCoreComponentModes = NativeCoreComponentModes(),
    val strictModes: NativeCoreStrictModes = NativeCoreStrictModes(),
    val parseWarning: String? = null,
) {
    val requiresLoad: Boolean
        get() =
            mode != NativeCoreMode.OFF &&
                NativeCoreComponent.entries.any { effectiveMode(it) != NativeCoreMode.OFF }

    fun effectiveMode(component: NativeCoreComponent): NativeCoreMode {
        if (mode == NativeCoreMode.OFF) return NativeCoreMode.OFF
        return when (componentModes.configuredMode(component)) {
            NativeCoreComponentMode.INHERIT -> mode
            NativeCoreComponentMode.OFF -> NativeCoreMode.OFF
            NativeCoreComponentMode.SHADOW -> NativeCoreMode.SHADOW
            NativeCoreComponentMode.ON -> NativeCoreMode.ON
        }
    }

    fun strictMode(component: NativeCoreComponent): Boolean =
        strictModes.isStrict(
            component = component,
            effectiveMode = effectiveMode(component),
        )

    companion object {
        private const val DEFAULT_LIBRARY_PATH = "/data/iris/lib/libiris_native_core.so"

        fun fromEnv(env: Map<String, String> = System.getenv()): NativeCoreModeConfig {
            val rawMode = env["IRIS_NATIVE_CORE"]?.trim()
            val mode = parseMode(rawMode)
            val warnings = mutableListOf<String>()
            if (!rawMode.isNullOrBlank() && mode == null) {
                warnings += "unsupported IRIS_NATIVE_CORE value: $rawMode"
            }
            val path = env["IRIS_NATIVE_LIB_PATH"]?.trim().orEmpty().ifBlank { DEFAULT_LIBRARY_PATH }
            return NativeCoreModeConfig(
                mode = mode ?: NativeCoreMode.OFF,
                libraryPath = path,
                componentModes = parseComponentModes(env, warnings),
                strictModes = parseStrictModes(env, warnings),
                parseWarning = warnings.joinToString("; ").ifBlank { null },
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

        private fun parseComponentModes(
            env: Map<String, String>,
            warnings: MutableList<String>,
        ): NativeCoreComponentModes =
            NativeCoreComponentModes(
                decrypt =
                    parseComponentMode(
                        env = env,
                        key = "IRIS_NATIVE_DECRYPT",
                        defaultMode = NativeCoreComponentMode.INHERIT,
                        allowInherit = true,
                        warnings = warnings,
                    ),
                routing =
                    parseComponentMode(
                        env = env,
                        key = "IRIS_NATIVE_ROUTING",
                        defaultMode = NativeCoreComponentMode.OFF,
                        allowInherit = false,
                        warnings = warnings,
                    ),
                parsers =
                    parseComponentMode(
                        env = env,
                        key = "IRIS_NATIVE_PARSERS",
                        defaultMode = NativeCoreComponentMode.OFF,
                        allowInherit = false,
                        warnings = warnings,
                    ),
                projections =
                    parseComponentMode(
                        env = env,
                        key = "IRIS_NATIVE_PROJECTIONS",
                        defaultMode = NativeCoreComponentMode.OFF,
                        allowInherit = false,
                        warnings = warnings,
                    ),
                webhookPayload =
                    parseComponentMode(
                        env = env,
                        key = "IRIS_NATIVE_WEBHOOK_PAYLOAD",
                        defaultMode = NativeCoreComponentMode.OFF,
                        allowInherit = false,
                        warnings = warnings,
                    ),
            )

        private fun parseStrictModes(
            env: Map<String, String>,
            warnings: MutableList<String>,
        ): NativeCoreStrictModes {
            val global = parseStrictFlag(env, "IRIS_NATIVE_STRICT", defaultValue = false, warnings = warnings)
            return NativeCoreStrictModes(
                global = global ?: false,
                decrypt = parseStrictFlag(env, "IRIS_NATIVE_STRICT_DECRYPT", defaultValue = null, warnings = warnings),
                routing = parseStrictFlag(env, "IRIS_NATIVE_STRICT_ROUTING", defaultValue = null, warnings = warnings),
                parsers = parseStrictFlag(env, "IRIS_NATIVE_STRICT_PARSERS", defaultValue = null, warnings = warnings),
                projections = parseStrictFlag(env, "IRIS_NATIVE_STRICT_PROJECTIONS", defaultValue = null, warnings = warnings),
                webhookPayload = parseStrictFlag(env, "IRIS_NATIVE_STRICT_WEBHOOK_PAYLOAD", defaultValue = null, warnings = warnings),
            )
        }

        private fun parseComponentMode(
            env: Map<String, String>,
            key: String,
            defaultMode: NativeCoreComponentMode,
            allowInherit: Boolean,
            warnings: MutableList<String>,
        ): NativeCoreComponentMode {
            val raw = env[key]?.trim()
            return when (raw?.lowercase()) {
                null,
                "",
                -> defaultMode

                "inherit" ->
                    if (allowInherit) {
                        NativeCoreComponentMode.INHERIT
                    } else {
                        warnings += "unsupported $key value: $raw"
                        defaultMode
                    }

                "off" -> NativeCoreComponentMode.OFF
                "shadow" -> NativeCoreComponentMode.SHADOW
                "on" -> NativeCoreComponentMode.ON
                else -> {
                    warnings += "unsupported $key value: $raw"
                    defaultMode
                }
            }
        }

        private fun parseStrictFlag(
            env: Map<String, String>,
            key: String,
            defaultValue: Boolean?,
            warnings: MutableList<String>,
        ): Boolean? {
            val raw = env[key]?.trim()
            return when (raw?.lowercase()) {
                null,
                "",
                -> defaultValue

                "on",
                "true",
                "1",
                "yes",
                -> true

                "off",
                "false",
                "0",
                "no",
                -> false

                else -> {
                    warnings += "unsupported $key value: $raw"
                    defaultValue
                }
            }
        }
    }
}
