package party.qwer.iris.imagebridge.runtime

import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Array
import java.lang.reflect.Modifier

object ChatRoomIntrospector {
    data class ScanResult(
        val className: String,
        val scannedAt: Long,
        val fields: List<FieldInfo>,
    )

    data class FieldInfo(
        val name: String,
        val type: String,
        val value: String? = null,
        val size: Int? = null,
        val elementType: String? = null,
        val nested: List<FieldInfo> = emptyList(),
        val elements: List<FieldInfo> = emptyList(),
    )

    fun scan(
        obj: Any,
        maxDepth: Int = 1,
    ): ScanResult =
        ScanResult(
            className = obj.javaClass.canonicalName ?: obj.javaClass.name,
            scannedAt = System.currentTimeMillis() / 1000,
            fields = scanFields(obj, maxDepth, currentDepth = 0),
        )

    fun scanJson(
        obj: Any,
        maxDepth: Int = 1,
    ): String = scan(obj, maxDepth).toJson().toString()

    private fun scanFields(
        obj: Any,
        maxDepth: Int,
        currentDepth: Int,
    ): List<FieldInfo> =
        obj.javaClass.declaredFields
            .filter { !Modifier.isStatic(it.modifiers) }
            .mapNotNull { field ->
                field.isAccessible = true
                try {
                    val value = field.get(obj)
                    val typeName = field.type.name
                    when {
                        field.type.isPrimitive || field.type == String::class.java -> {
                            FieldInfo(name = field.name, type = typeName, value = value?.toString())
                        }
                        value is Collection<*> -> {
                            val elemType = value.firstOrNull()?.javaClass?.name
                            FieldInfo(
                                name = field.name,
                                type = typeName,
                                size = value.size,
                                elementType = elemType,
                                elements = sampleCollectionElements(value, maxDepth, currentDepth),
                            )
                        }
                        value != null && value.javaClass.isArray -> {
                            FieldInfo(
                                name = field.name,
                                type = typeName,
                                size = Array.getLength(value),
                                elementType = value.javaClass.componentType?.name,
                                elements = sampleArrayElements(value, maxDepth, currentDepth),
                            )
                        }
                        value is Map<*, *> -> {
                            FieldInfo(name = field.name, type = typeName, size = value.size)
                        }
                        value != null && currentDepth < maxDepth -> {
                            FieldInfo(
                                name = field.name,
                                type = typeName,
                                nested = scanFields(value, maxDepth, currentDepth + 1),
                            )
                        }
                        else -> {
                            FieldInfo(name = field.name, type = typeName, value = value?.toString())
                        }
                    }
                } catch (_: Exception) {
                    FieldInfo(name = field.name, type = field.type.name, value = "<inaccessible>")
                }
            }

    private fun sampleCollectionElements(
        values: Collection<*>,
        maxDepth: Int,
        currentDepth: Int,
    ): List<FieldInfo> =
        values.take(MAX_COLLECTION_SAMPLE_SIZE).mapIndexed { index, value ->
            describeElement(index, value, maxDepth, currentDepth)
        }

    private fun sampleArrayElements(
        array: Any,
        maxDepth: Int,
        currentDepth: Int,
    ): List<FieldInfo> =
        buildList {
            val size = minOf(Array.getLength(array), MAX_COLLECTION_SAMPLE_SIZE)
            repeat(size) { index ->
                add(describeElement(index, Array.get(array, index), maxDepth, currentDepth))
            }
        }

    private fun describeElement(
        index: Int,
        value: Any?,
        maxDepth: Int,
        currentDepth: Int,
    ): FieldInfo {
        val typeName = value?.javaClass?.name ?: "null"
        return when {
            value == null || value.javaClass.isPrimitive || value is String || value is Number || value is Boolean || value is Char -> {
                FieldInfo(name = "[$index]", type = typeName, value = value?.toString())
            }
            currentDepth < maxDepth -> {
                FieldInfo(
                    name = "[$index]",
                    type = typeName,
                    nested = scanFields(value, maxDepth, currentDepth + 1),
                )
            }
            else -> {
                FieldInfo(name = "[$index]", type = typeName, value = value.toString())
            }
        }
    }

    private fun ScanResult.toJson(): JSONObject =
        JSONObject().apply {
            put("className", className)
            put("scannedAt", scannedAt)
            put("fields", JSONArray(fields.map { it.toJson() }))
        }

    private fun FieldInfo.toJson(): JSONObject =
        JSONObject().apply {
            put("name", name)
            put("type", type)
            value?.let { put("value", it) }
            size?.let { put("size", it) }
            elementType?.let { put("elementType", it) }
            if (nested.isNotEmpty()) {
                put("nested", JSONArray(nested.map { it.toJson() }))
            }
            if (elements.isNotEmpty()) {
                put("elements", JSONArray(elements.map { it.toJson() }))
            }
        }

    private const val MAX_COLLECTION_SAMPLE_SIZE = 3
}
