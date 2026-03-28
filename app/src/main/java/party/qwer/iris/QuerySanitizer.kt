package party.qwer.iris

private val SAFE_PRAGMA_PATTERNS =
    listOf(
        Regex("""(?is)^PRAGMA\s+table_info\s*\(\s*[A-Za-z_][A-Za-z0-9_]*\s*\)\s*$"""),
        Regex("""(?is)^PRAGMA\s+index_list\s*\(\s*[A-Za-z_][A-Za-z0-9_]*\s*\)\s*$"""),
        Regex("""(?is)^PRAGMA\s+compile_options\s*$"""),
    )

private val WRITE_KEYWORD_PATTERN =
    Regex(
        """\b(INSERT|UPDATE|DELETE|DROP|CREATE|ALTER|ATTACH|DETACH|REINDEX|VACUUM)\b""",
        RegexOption.IGNORE_CASE,
    )

internal fun isReadOnlyQuery(query: String): Boolean {
    val normalized = query.trim()
    if (normalized.isBlank()) return false
    if (';' in normalized) return false

    if (normalized.startsWith("PRAGMA", ignoreCase = true)) {
        return SAFE_PRAGMA_PATTERNS.any { pattern ->
            pattern.matches(normalized)
        }
    }

    val upper = normalized.uppercase()
    if (!upper.startsWith("SELECT") && !upper.startsWith("WITH")) return false
    return !WRITE_KEYWORD_PATTERN.containsMatchIn(normalized)
}
