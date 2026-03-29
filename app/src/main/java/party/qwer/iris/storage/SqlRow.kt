package party.qwer.iris.storage

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

class SqlRow(
    private val columnIndex: Map<String, Int>,
    private val values: List<JsonElement?>,
) {
    fun string(name: String): String? =
        columnIndex[name]?.let { idx ->
            values.getOrNull(idx)?.let { elem ->
                (elem as? JsonPrimitive)?.content
            }
        }

    fun long(name: String): Long? = string(name)?.toLongOrNull()

    fun int(name: String): Int? = string(name)?.toIntOrNull()
}
