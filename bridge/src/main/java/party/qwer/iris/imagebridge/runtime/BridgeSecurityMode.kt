package party.qwer.iris.imagebridge.runtime

internal enum class BridgeSecurityMode {
    DEVELOPMENT,
    PRODUCTION,
    ;

    companion object {
        fun fromEnv(raw: String? = System.getenv("IRIS_BRIDGE_SECURITY_MODE")): BridgeSecurityMode =
            when (raw?.trim()?.lowercase()) {
                "production", "prod" -> PRODUCTION
                else -> DEVELOPMENT
            }
    }
}
