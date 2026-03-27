package party.qwer.iris.bridge

import android.util.Log
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

internal class ChatRoomResolver(
    private val loader: ClassLoader,
    private val classLookup: (String) -> Class<*> = { className -> Class.forName(className, true, loader) },
) {
    companion object {
        private const val TAG = "IrisBridge"
    }

    private val classCache = ConcurrentHashMap<String, Class<*>>()
    private val roomDaoAccessorCache = ConcurrentHashMap<Class<*>, Method>()
    private val roomEntityLookupCache = ConcurrentHashMap<Class<*>, Method>()
    private val roomEntityResolverCache = ConcurrentHashMap<Class<*>, RoomEntityResolver>()
    private val managerBroadResolverCache = ConcurrentHashMap<Class<*>, Method>()
    private val managerDirectResolverCache = ConcurrentHashMap<Class<*>, Method>()

    private val masterDatabaseClass by lazy { loadClass("com.kakao.talk.database.MasterDatabase") }
    private val masterDatabaseInstanceField by lazy { masterDatabaseClass.findSingletonField() }
    private val chatRoomClass by lazy { loadClass("hp.t") }
    private val managerClass by lazy { loadClass("hp.J0") }
    private val managerAccessor by lazy { resolveManagerAccessor() }

    fun resolve(roomId: Long): Any? {
        runCatching { resolveFromDatabase(roomId) }
            .onFailure { Log.e(TAG, "primary room resolver failed: ${it.message}", it) }
            .getOrNull()
            ?.let { return it }

        runCatching { resolveFromManager(roomId) }
            .onFailure { Log.e(TAG, "hp.J0 resolver failed: ${it.message}", it) }
            .getOrNull()
            ?.let { return it }

        return null
    }

    private fun resolveFromDatabase(roomId: Long): Any? {
        val database = masterDatabaseInstanceField.get(null) ?: error("MasterDatabase not initialized")
        val roomDao =
            roomDaoAccessorCache
                .computeIfAbsent(database.javaClass) { dbClass ->
                    dbClass.getMethod("O")
                }.invoke(database)
        val entity =
            roomEntityLookupCache
                .computeIfAbsent(roomDao.javaClass) { daoClass ->
                    daoClass.getMethod("h", Long::class.javaPrimitiveType)
                }.invoke(roomDao, roomId) ?: return null
        val resolver =
            roomEntityResolverCache.computeIfAbsent(entity.javaClass) { entityClass ->
                resolveRoomEntityResolver(entityClass)
            }
        return resolver.resolve(entity)
    }

    private fun resolveFromManager(roomId: Long): Any? {
        val manager = managerAccessor()
        val broadResolver =
            managerBroadResolverCache.computeIfAbsent(manager.javaClass) { managerRuntimeClass ->
                managerRuntimeClass.getMethod(
                    "e0",
                    Long::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType,
                )
            }
        val broadResult = broadResolver.invoke(manager, roomId, true, true)
        if (broadResult != null) {
            return broadResult
        }
        val directResolver =
            managerDirectResolverCache.computeIfAbsent(manager.javaClass) { managerRuntimeClass ->
                managerRuntimeClass.getMethod("d0", Long::class.javaPrimitiveType)
            }
        return directResolver.invoke(manager, roomId)
    }

    private fun resolveRoomEntityResolver(entityClass: Class<*>): RoomEntityResolver {
        chatRoomClass.declaredFields
            .asSequence()
            .filter { Modifier.isStatic(it.modifiers) }
            .mapNotNull { field -> field.readStaticValue() }
            .firstOrNull { candidate ->
                candidate.javaClass.methods.any { method ->
                    method.name == "c" &&
                        method.parameterCount == 1 &&
                        chatRoomClass.isAssignableFrom(method.returnType) &&
                        method.parameterTypes[0].isAssignableFrom(entityClass)
                }
            }?.let { companion ->
                val resolverMethod =
                    companion.javaClass.methods.first { method ->
                        method.name == "c" &&
                            method.parameterCount == 1 &&
                            chatRoomClass.isAssignableFrom(method.returnType) &&
                            method.parameterTypes[0].isAssignableFrom(entityClass)
                    }
                return CompanionRoomEntityResolver(companion, resolverMethod)
            }

        val constructor =
            chatRoomClass.declaredConstructors.firstOrNull { candidate ->
                candidate.parameterTypes.size == 1 &&
                    candidate.parameterTypes[0].isAssignableFrom(entityClass)
            } ?: error("hp.t companion/constructor resolver not found")
        constructor.isAccessible = true
        return ConstructorRoomEntityResolver(constructor)
    }

    private fun resolveManagerAccessor(): () -> Any {
        val companion =
            managerClass.declaredFields
                .asSequence()
                .filter { Modifier.isStatic(it.modifiers) }
                .mapNotNull { field -> field.readStaticValue() }
                .firstOrNull { candidate ->
                    candidate.javaClass.methods.any { method ->
                        method.name == "j" &&
                            method.parameterCount == 0 &&
                            method.returnType == managerClass
                    }
                }
        if (companion != null) {
            val accessor =
                companion.javaClass.methods.first { method ->
                    method.name == "j" &&
                        method.parameterCount == 0 &&
                        method.returnType == managerClass
                }
            return { accessor.invoke(companion) ?: error("hp.J0 companion j() returned null") }
        }
        val staticAccessor =
            managerClass.methods.firstOrNull { method ->
                Modifier.isStatic(method.modifiers) &&
                    method.parameterCount == 0 &&
                    method.returnType == managerClass
            } ?: error("hp.J0 singleton accessor not found")
        return { staticAccessor.invoke(null) ?: error("hp.J0 static accessor returned null") }
    }

    private fun Field.readStaticValue(): Any? =
        runCatching {
            isAccessible = true
            get(null)
        }.getOrNull()

    private fun Class<*>.findSingletonField(): Field {
        val field =
            declaredFields.firstOrNull { candidate ->
                Modifier.isStatic(candidate.modifiers) && candidate.type == this
            } ?: error("$name instance field not found")
        field.isAccessible = true
        return field
    }

    private fun loadClass(className: String): Class<*> =
        classCache.computeIfAbsent(className) { name ->
            classLookup(name)
        }

    private fun interface RoomEntityResolver {
        fun resolve(entity: Any): Any?
    }

    private class CompanionRoomEntityResolver(
        private val companion: Any,
        private val method: Method,
    ) : RoomEntityResolver {
        override fun resolve(entity: Any): Any? = method.invoke(companion, entity)
    }

    private class ConstructorRoomEntityResolver(
        private val constructor: Constructor<*>,
    ) : RoomEntityResolver {
        override fun resolve(entity: Any): Any? = constructor.newInstance(entity)
    }
}
