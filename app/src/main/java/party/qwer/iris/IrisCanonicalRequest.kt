package party.qwer.iris

internal data class IrisCanonicalRequest(
    val method: String,
    val target: String,
    val timestampMs: String,
    val nonce: String,
    val bodySha256Hex: String,
) {
    fun serialize(): String =
        listOf(
            method.uppercase(),
            target,
            timestampMs,
            nonce,
            bodySha256Hex,
        ).joinToString("\n")
}
