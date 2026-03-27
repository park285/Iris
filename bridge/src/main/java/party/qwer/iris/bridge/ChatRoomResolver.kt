package party.qwer.iris.bridge

import android.util.Log
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

internal class ChatRoomResolver(
    private val registry: KakaoClassRegistry,
) {
    companion object {
        private const val TAG = "IrisBridge"
    }

    private val roomEntityResolverCache = ConcurrentHashMap<Class<*>, RoomEntityResolver>()
    private val managerAccessor by lazy { resolveManagerAccessor() }

    fun resolve(roomId: Long): Any? {
        runCatching { resolveFromDatabase(roomId) }
            .onFailure { Log.e(TAG, "primary room resolver failed: ${it.message}", it) }
            .getOrNull()
            ?.let { return it }

        runCatching { resolveFromManager(roomId) }
            .onFailure { Log.e(TAG, "manager resolver failed: ${it.message}", it) }
            .getOrNull()
            ?.let { return it }

        return null
    }

    private fun resolveFromDatabase(roomId: Long): Any? {
        val database =
            registry.masterDbSingletonField.get(null)
                ?: error("MasterDatabase not initialized")
        val roomDao = registry.roomDaoMethod.invoke(database)
        val entity = registry.entityLookupMethod.invoke(roomDao, roomId) ?: return null
        val resolver =
            roomEntityResolverCache.computeIfAbsent(entity.javaClass) { entityClass ->
                resolveRoomEntityResolver(entityClass)
            }
        return resolver.resolve(entity)
    }

    private fun resolveFromManager(roomId: Long): Any? {
        val manager = managerAccessor()
        val broadResult = registry.broadRoomResolverMethod.invoke(manager, roomId, true, true)
        if (broadResult != null) return broadResult
        return registry.directRoomResolverMethod.invoke(manager, roomId)
    }

    private fun resolveRoomEntityResolver(entityClass: Class<*>): RoomEntityResolver {
        registry.chatRoomClass.declaredFields
            .asSequence()
            .filter { Modifier.isStatic(it.modifiers) }
            .mapNotNull { field -> field.readStaticValue() }
            .firstOrNull { candidate ->
                candidate.javaClass.methods.any { method ->
                    method.parameterCount == 1 &&
                        registry.chatRoomClass.isAssignableFrom(method.returnType) &&
                        method.parameterTypes[0].isAssignableFrom(entityClass)
                }
            }?.let { companion ->
                val resolverMethod =
                    companion.javaClass.methods.first { method ->
                        method.parameterCount == 1 &&
                            registry.chatRoomClass.isAssignableFrom(method.returnType) &&
                            method.parameterTypes[0].isAssignableFrom(entityClass)
                    }
                return CompanionRoomEntityResolver(companion, resolverMethod)
            }

        val constructor =
            registry.chatRoomClass.declaredConstructors.firstOrNull { candidate ->
                candidate.parameterTypes.size == 1 &&
                    candidate.parameterTypes[0].isAssignableFrom(entityClass)
            } ?: error("ChatRoom companion/constructor resolver not found for ${entityClass.name}")
        constructor.isAccessible = true
        return ConstructorRoomEntityResolver(constructor)
    }

    private fun resolveManagerAccessor(): () -> Any {
        val managerClass = registry.chatRoomManagerClass
        val companion =
            managerClass.declaredFields
                .asSequence()
                .filter { Modifier.isStatic(it.modifiers) }
                .mapNotNull { field -> field.readStaticValue() }
                .firstOrNull { candidate ->
                    candidate.javaClass.methods.any { method ->
                        method.parameterCount == 0 && method.returnType == managerClass
                    }
                }
        if (companion != null) {
            val accessor =
                companion.javaClass.methods.first { method ->
                    method.parameterCount == 0 && method.returnType == managerClass
                }
            return { accessor.invoke(companion) ?: error("ChatRoomManager companion accessor returned null") }
        }
        val staticAccessor =
            managerClass.methods.firstOrNull { method ->
                Modifier.isStatic(method.modifiers) &&
                    method.parameterCount == 0 &&
                    method.returnType == managerClass
            } ?: error("ChatRoomManager singleton accessor not found")
        return { staticAccessor.invoke(null) ?: error("ChatRoomManager static accessor returned null") }
    }

    private fun Field.readStaticValue(): Any? =
        runCatching {
            isAccessible = true
            get(null)
        }.getOrNull()

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
