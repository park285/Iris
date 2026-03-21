package party.qwer.iris

internal val SAFE_PRAGMAS =
    setOf(
        "table_info",
        "table_xinfo",
        "index_list",
        "index_info",
        "foreign_key_list",
        "compile_options",
        "database_list",
        "collation_list",
        "encoding",
        "page_size",
        "page_count",
        "max_page_count",
        "freelist_count",
    )

private val WRITE_KEYWORD_PATTERN =
    Regex(
        """\b(INSERT|UPDATE|DELETE|DROP|CREATE|ALTER|ATTACH|DETACH|REINDEX|VACUUM)\b""",
        RegexOption.IGNORE_CASE,
    )

internal fun isReadOnlyQuery(query: String): Boolean {
    val normalized = query.trimStart()
    if (normalized.isBlank()) return false
    val upper = normalized.uppercase()

    if (upper.startsWith("PRAGMA")) {
        val pragmaBody = normalized.substringAfter("PRAGMA", "").trimStart()
        val pragmaName =
            pragmaBody
                .split('(', '=', ' ', ';')
                .first()
                .trim()
                .lowercase()
        return pragmaName in SAFE_PRAGMAS
    }

    if (!upper.startsWith("SELECT") && !upper.startsWith("WITH")) return false
    return !WRITE_KEYWORD_PATTERN.containsMatchIn(normalized)
}
