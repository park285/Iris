package party.qwer.iris.nativecore

internal enum class NativeCoreMode {
    OFF,
    SHADOW,
    ON,
}

internal enum class NativeCoreComponent(
    val id: String,
) {
    DECRYPT("decrypt"),
    ROUTING("routing"),
    PARSERS("parsers"),
    PROJECTIONS("projections"),
    WEBHOOK_PAYLOAD("webhookPayload"),
}

internal enum class NativeCoreComponentMode {
    INHERIT,
    OFF,
    SHADOW,
    ON,
}
