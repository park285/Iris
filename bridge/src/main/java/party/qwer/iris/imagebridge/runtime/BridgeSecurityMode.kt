package party.qwer.iris.imagebridge.runtime

internal enum class BridgeSecurityMode {
    DEVELOPMENT,
    PRODUCTION,
    ;

    companion object {
        fun fromEnv(raw: String? = System.getenv("IRIS_BRIDGE_SECURITY_MODE")): BridgeSecurityMode =
            when (raw?.trim()?.lowercase()) {
                "development", "dev" -> DEVELOPMENT
                else -> PRODUCTION
            }
    }
}
