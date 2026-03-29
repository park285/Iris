package party.qwer.iris

import io.ktor.http.Parameters
import io.ktor.http.encodeURLParameter

internal fun canonicalRequestTarget(
    path: String,
    queryParameters: Parameters,
): String {
    val encodedQuery =
        queryParameters
            .entries()
            .asSequence()
            .flatMap { (name, values) ->
                if (values.isEmpty()) {
                    sequenceOf(name.encodeURLParameter() to null)
                } else {
                    values.asSequence().map { value ->
                        name.encodeURLParameter() to value.encodeURLParameter()
                    }
                }
            }.sortedWith(compareBy({ it.first }, { it.second.orEmpty() }))
            .joinToString("&") { (name, value) ->
                if (value == null) {
                    name
                } else {
                    "$name=$value"
                }
            }
    return if (encodedQuery.isBlank()) path else "$path?$encodedQuery"
}
