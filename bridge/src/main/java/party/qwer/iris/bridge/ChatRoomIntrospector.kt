package party.qwer.iris.bridge

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
    )

    fun scan(obj: Any, maxDepth: Int = 1): ScanResult {
        return ScanResult(
            className = obj.javaClass.canonicalName ?: obj.javaClass.name,
            scannedAt = System.currentTimeMillis() / 1000,
            fields = scanFields(obj, maxDepth, currentDepth = 0),
        )
    }

    private fun scanFields(obj: Any, maxDepth: Int, currentDepth: Int): List<FieldInfo> {
        return obj.javaClass.declaredFields
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
                                name = field.name, type = typeName,
                                size = value.size, elementType = elemType,
                            )
                        }
                        value is Map<*, *> -> {
                            FieldInfo(name = field.name, type = typeName, size = value.size)
                        }
                        value != null && currentDepth < maxDepth -> {
                            FieldInfo(
                                name = field.name, type = typeName,
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
    }
}
