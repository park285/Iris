package party.qwer.iris.bridge

import android.util.Log
import java.lang.reflect.Field

/**
 * Enumerates class names from DEX files in the given classloader
 * and finds classes matching a signature predicate.
 */
internal class DexClassScanner(
    private val classLoader: ClassLoader,
) {
    companion object {
        private const val TAG = "IrisBridge"
        private val SKIP_PREFIXES =
            arrayOf(
                "android.",
                "java.",
                "javax.",
                "kotlin.",
                "kotlinx.",
                "androidx.",
                "com.google.",
                "org.json.",
                "dalvik.",
                "de.robv.android.xposed.",
                "party.qwer.iris.",
            )
    }

    private val classNames: List<String> by lazy { enumerateClassNames() }

    fun find(predicate: (Class<*>) -> Boolean): Class<*>? {
        for (name in classNames) {
            if (SKIP_PREFIXES.any { name.startsWith(it) }) continue
            val clazz =
                runCatching {
                    Class.forName(name, false, classLoader)
                }.getOrNull() ?: continue
            if (predicate(clazz)) return clazz
        }
        return null
    }

    private fun enumerateClassNames(): List<String> =
        runCatching {
            enumerateViaDexPathList()
        }.getOrElse { error ->
            Log.e(TAG, "DEX enumeration failed: ${error.message}", error)
            emptyList()
        }

    private fun enumerateViaDexPathList(): List<String> {
        val result = mutableListOf<String>()
        val pathListField =
            classLoader.javaClass.findFieldInHierarchy("pathList")
                ?: error("pathList field not found on ${classLoader.javaClass.name}")
        pathListField.isAccessible = true
        val pathList = pathListField.get(classLoader)

        val dexElementsField = pathList.javaClass.getDeclaredField("dexElements")
        dexElementsField.isAccessible = true
        val dexElements = dexElementsField.get(pathList) as? Array<*> ?: return emptyList()

        for (element in dexElements) {
            if (element == null) continue
            val dexFileField =
                runCatching {
                    element.javaClass.getDeclaredField("dexFile").apply { isAccessible = true }
                }.getOrNull() ?: continue
            val dexFile = dexFileField.get(element) ?: continue
            val entriesMethod = dexFile.javaClass.getMethod("entries")

            @Suppress("UNCHECKED_CAST")
            val entries = entriesMethod.invoke(dexFile) as? java.util.Enumeration<String> ?: continue
            while (entries.hasMoreElements()) {
                result.add(entries.nextElement())
            }
        }
        Log.i(TAG, "DEX scan: enumerated ${result.size} class names")
        return result
    }

    private fun Class<*>.findFieldInHierarchy(name: String): Field? {
        var current: Class<*>? = this
        while (current != null) {
            runCatching {
                return current.getDeclaredField(name)
            }
            current = current.superclass
        }
        return null
    }
}
