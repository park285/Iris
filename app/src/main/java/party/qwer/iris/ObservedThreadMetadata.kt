package party.qwer.iris

internal data class ObservedThreadMetadata(
    val threadId: String,
    val threadScope: Int? = null,
)

internal fun resolveObservedThreadMetadata(
    logEntry: KakaoDB.ChatLogEntry,
    enc: Int,
): ObservedThreadMetadata? {
    val supplement = decryptObservedSupplement(logEntry.supplement, enc, logEntry.userId)
    val directThreadId =
        logEntry.threadId
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    val supplementThreadId =
        if (logEntry.messageType == "1") {
            THREAD_ID_PATTERN
                .find(supplement.orEmpty())
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        } else {
            null
        }
    val resolvedThreadId = directThreadId ?: supplementThreadId ?: return null

    val resolvedScope =
        if (logEntry.messageType == "1") {
            SCOPE_PATTERN
                .find(supplement.orEmpty())
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?.takeIf { it > 0 }
        } else {
            null
        }

    return ObservedThreadMetadata(
        threadId = resolvedThreadId,
        threadScope = resolvedScope,
    )
}

private fun decryptObservedSupplement(
    supplement: String?,
    enc: Int,
    userId: Long,
): String? {
    val raw = supplement?.trim().orEmpty()
    if (raw.isEmpty() || raw == "{}") {
        return null
    }
    if (enc <= 0) {
        return raw
    }
    return runCatching {
        KakaoDecrypt.decrypt(enc, raw, userId)
    }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() && it != "{}" }
}

private val THREAD_ID_PATTERN = Regex("""["']threadId["']\s*:\s*["']([^"']+)["']""")
private val SCOPE_PATTERN = Regex("""["']scope["']\s*:\s*([0-9]+)""")
