@file:Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")

package party.qwer.iris.bridge

import android.util.Log
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

internal class KakaoClassRegistry(
    val mediaItemClass: Class<*>,
    val function0Class: Class<*>,
    val function1Class: Class<*>,
    val masterDatabaseClass: Class<*>,
    val writeTypeClass: Class<*>,
    val listenerClass: Class<*>,
    val chatMediaSenderClass: Class<*>,
    val messageTypeClass: Class<*>,
    val chatRoomManagerClass: Class<*>,
    val chatRoomClass: Class<*>,
    val singleSendMethod: Method,
    val multiSendMethod: Method,
    val mediaItemConstructor: Constructor<*>,
    val masterDbSingletonField: Field,
    val roomDaoMethod: Method,
    val entityLookupMethod: Method,
    val broadRoomResolverMethod: Method,
    val directRoomResolverMethod: Method,
    val photoType: Any,
    val multiPhotoType: Any,
    val writeTypeNone: Any,
) {
    companion object {
        private const val TAG = "IrisBridge"

        fun discover(classLoader: ClassLoader): KakaoClassRegistry {
            Log.i(TAG, "KakaoClassRegistry.discover start")
            val scanner = DexClassScanner(classLoader)

            val mediaItem = stableClass(classLoader, "com.kakao.talk.model.media.MediaItem")
            val function0 = stableClass(classLoader, "kotlin.jvm.functions.Function0")
            val function1 = stableClass(classLoader, "kotlin.jvm.functions.Function1")
            val masterDb = stableClass(classLoader, "com.kakao.talk.database.MasterDatabase")
            val writeType = stableClass(classLoader, "com.kakao.talk.manager.send.ChatSendingLogRequest\$c")
            val listener = stableClass(classLoader, "com.kakao.talk.manager.send.m")

            val messageType =
                discoverClass(
                    classLoader,
                    scanner,
                    lastKnownNames = arrayOf("Op.EnumC16810c", "Op.c"),
                    label = "MessageType",
                ) { clazz ->
                    clazz.isEnum && hasEnumConstants(clazz, "Photo", "MultiPhoto")
                }

            val chatMediaSender =
                discoverClass(
                    classLoader,
                    scanner,
                    lastKnownNames = arrayOf("bh.c"),
                    label = "ChatMediaSender",
                ) { clazz ->
                    clazz.constructors.any { ctor ->
                        ctor.parameterTypes.size == 4 &&
                            ctor.parameterTypes[1] == java.lang.Long::class.java &&
                            ctor.parameterTypes[2] == function0 &&
                            ctor.parameterTypes[3] == function1
                    } &&
                        clazz.declaredMethods.any { m ->
                            !Modifier.isStatic(m.modifiers) &&
                                m.parameterTypes.size == 2 &&
                                m.parameterTypes[0] == mediaItem &&
                                m.parameterTypes[1] == Boolean::class.javaPrimitiveType
                        }
                }

            val chatRoomManager =
                discoverClass(
                    classLoader,
                    scanner,
                    lastKnownNames = arrayOf("hp.J0"),
                    label = "ChatRoomManager",
                ) { clazz ->
                    hasSelfReturningAccessor(clazz) &&
                        clazz.declaredMethods.any { m ->
                            !Modifier.isStatic(m.modifiers) &&
                                m.parameterTypes.contentEquals(
                                    arrayOf(
                                        Long::class.javaPrimitiveType,
                                        Boolean::class.javaPrimitiveType,
                                        Boolean::class.javaPrimitiveType,
                                    ),
                                )
                        }
                }

            val broadResolver =
                chatRoomManager.declaredMethods.firstOrNull { m ->
                    !Modifier.isStatic(m.modifiers) &&
                        m.parameterTypes.contentEquals(
                            arrayOf(
                                Long::class.javaPrimitiveType,
                                Boolean::class.javaPrimitiveType,
                                Boolean::class.javaPrimitiveType,
                            ),
                        )
                } ?: error(
                    "ChatRoomManager broad resolver not found: expected (long,boolean,boolean) on ${chatRoomManager.name}",
                )
            val chatRoom = broadResolver.returnType
            Log.i(TAG, "ChatRoom derived as ${chatRoom.name}")

            val directResolver =
                chatRoomManager.declaredMethods.firstOrNull { m ->
                    m != broadResolver &&
                        !Modifier.isStatic(m.modifiers) &&
                        m.parameterTypes.contentEquals(arrayOf(Long::class.javaPrimitiveType)) &&
                        chatRoom.isAssignableFrom(m.returnType)
                } ?: error(
                    "ChatRoomManager direct resolver not found: expected (long)->ChatRoom on ${chatRoomManager.name}",
                )

            val singleSend =
                chatMediaSender.declaredMethods.firstOrNull { m ->
                    !Modifier.isStatic(m.modifiers) &&
                        m.parameterTypes.size == 2 &&
                        m.parameterTypes[0] == mediaItem &&
                        m.parameterTypes[1] == Boolean::class.javaPrimitiveType
                } ?: error(
                    "ChatMediaSender single send not found: expected (MediaItem,boolean) on ${chatMediaSender.name}",
                )

            val multiSend =
                chatMediaSender.declaredMethods.firstOrNull { m ->
                    !Modifier.isStatic(m.modifiers) &&
                        m.parameterCount == 9 &&
                        m.parameterTypes[0] == List::class.java &&
                        m.parameterTypes[1] == messageType
                } ?: error(
                    "ChatMediaSender multi send not found: expected 9-param (List,MessageType,...) on ${chatMediaSender.name}",
                )

            val mediaItemCtor =
                mediaItem.getConstructor(
                    String::class.java,
                    Long::class.javaPrimitiveType,
                )

            val masterDbField =
                masterDb.declaredFields
                    .firstOrNull { field ->
                        Modifier.isStatic(field.modifiers) && field.type == masterDb
                    }?.apply { isAccessible = true }
                    ?: error("MasterDatabase singleton field not found on ${masterDb.name}")

            val roomDao =
                masterDb.methods.firstOrNull { m ->
                    !Modifier.isStatic(m.modifiers) &&
                        m.parameterCount == 0 &&
                        m.returnType != Void.TYPE &&
                        m.returnType != masterDb &&
                        m.returnType != Any::class.java &&
                        m.returnType.methods.any { daoMethod ->
                            daoMethod.parameterTypes.contentEquals(arrayOf(Long::class.javaPrimitiveType)) &&
                                daoMethod.returnType != Void.TYPE
                        }
                } ?: error(
                    "MasterDatabase roomDao accessor not found: expected 0-param method returning DAO on ${masterDb.name}",
                )

            val daoClass = roomDao.returnType
            val entityLookup =
                daoClass.methods.firstOrNull { m ->
                    !Modifier.isStatic(m.modifiers) &&
                        m.parameterTypes.contentEquals(arrayOf(Long::class.javaPrimitiveType)) &&
                        m.returnType != Void.TYPE &&
                        m.returnType != daoClass
                } ?: error(
                    "RoomDao entity lookup not found: expected (long)->entity on ${daoClass.name}",
                )

            val photoConst = requireEnumConstant(messageType, "Photo")
            val multiPhotoConst = requireEnumConstant(messageType, "MultiPhoto")
            val writeNoneConst = requireEnumConstant(writeType, "None")

            Log.i(
                TAG,
                "KakaoClassRegistry.discover complete — " +
                    "ChatMediaSender=${chatMediaSender.name}, " +
                    "MessageType=${messageType.name}, " +
                    "ChatRoomManager=${chatRoomManager.name}, " +
                    "ChatRoom=${chatRoom.name}",
            )

            return KakaoClassRegistry(
                mediaItemClass = mediaItem,
                function0Class = function0,
                function1Class = function1,
                masterDatabaseClass = masterDb,
                writeTypeClass = writeType,
                listenerClass = listener,
                chatMediaSenderClass = chatMediaSender,
                messageTypeClass = messageType,
                chatRoomManagerClass = chatRoomManager,
                chatRoomClass = chatRoom,
                singleSendMethod = singleSend,
                multiSendMethod = multiSend,
                mediaItemConstructor = mediaItemCtor,
                masterDbSingletonField = masterDbField,
                roomDaoMethod = roomDao,
                entityLookupMethod = entityLookup,
                broadRoomResolverMethod = broadResolver,
                directRoomResolverMethod = directResolver,
                photoType = photoConst,
                multiPhotoType = multiPhotoConst,
                writeTypeNone = writeNoneConst,
            )
        }

        private fun stableClass(
            loader: ClassLoader,
            name: String,
        ): Class<*> = Class.forName(name, true, loader)

        private fun discoverClass(
            classLoader: ClassLoader,
            scanner: DexClassScanner,
            lastKnownNames: Array<String>,
            label: String,
            signatureMatcher: (Class<*>) -> Boolean,
        ): Class<*> {
            for (name in lastKnownNames) {
                val clazz =
                    runCatching {
                        Class.forName(name, false, classLoader)
                    }.getOrNull()
                if (clazz != null && signatureMatcher(clazz)) {
                    Log.i(TAG, "$label found at known name: $name")
                    return clazz
                }
            }
            Log.w(TAG, "$label not found at known names ${lastKnownNames.toList()}, starting DEX scan")
            return scanner.find(signatureMatcher)
                ?: error("$label not found by signature (last known: ${lastKnownNames.toList()})")
        }

        private fun hasEnumConstants(
            clazz: Class<*>,
            vararg names: String,
        ): Boolean {
            val constants = clazz.enumConstants ?: return false
            val constantNames = constants.map { (it as Enum<*>).name }.toSet()
            return names.all { it in constantNames }
        }

        private fun hasSelfReturningAccessor(clazz: Class<*>): Boolean {
            // Check for static method returning self (direct singleton accessor)
            val hasStaticSelfAccessor =
                clazz.methods.any { method ->
                    Modifier.isStatic(method.modifiers) &&
                        method.parameterCount == 0 &&
                        method.returnType == clazz
                }
            if (hasStaticSelfAccessor) return true
            // Check for companion-style accessor: static field whose declared type
            // has a 0-param method returning the enclosing class
            return clazz.declaredFields.any { field ->
                Modifier.isStatic(field.modifiers) &&
                    field.type != clazz &&
                    field.type.methods.any { method ->
                        method.parameterCount == 0 && method.returnType == clazz
                    }
            }
        }

        @Suppress("UNCHECKED_CAST")
        private fun requireEnumConstant(
            enumClass: Class<*>,
            name: String,
        ): Any =
            enumClass.enumConstants?.firstOrNull { (it as Enum<*>).name == name }
                ?: error("enum constant $name not found in ${enumClass.name}")
    }
}
