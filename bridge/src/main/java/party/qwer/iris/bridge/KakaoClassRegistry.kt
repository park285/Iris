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
                    matchesChatMediaSenderClass(
                        clazz = clazz,
                        mediaItemClass = mediaItem,
                        function0Class = function0,
                        function1Class = function1,
                    )
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
                selectMethodCandidate(
                    label = "ChatRoomManager broad resolver on ${chatRoomManager.name}",
                    candidates =
                        chatRoomManager.declaredMethods.filter { m ->
                            !Modifier.isStatic(m.modifiers) &&
                                m.parameterTypes.contentEquals(
                                    arrayOf(
                                        Long::class.javaPrimitiveType,
                                        Boolean::class.javaPrimitiveType,
                                        Boolean::class.javaPrimitiveType,
                                    ),
                                )
                        },
                    preferredNames = setOf("e0"),
                )
            val chatRoom = broadResolver.returnType
            Log.i(TAG, "ChatRoom derived as ${chatRoom.name}")

            val directResolver =
                selectMethodCandidate(
                    label = "ChatRoomManager direct resolver on ${chatRoomManager.name}",
                    candidates =
                        chatRoomManager.declaredMethods.filter { m ->
                            m != broadResolver &&
                                !Modifier.isStatic(m.modifiers) &&
                                m.parameterTypes.contentEquals(arrayOf(Long::class.javaPrimitiveType)) &&
                                chatRoom.isAssignableFrom(m.returnType)
                        },
                    preferredNames = setOf("d0"),
                )

            val (singleSend, multiSend) =
                resolveChatMediaSendMethods(
                    chatMediaSenderClass = chatMediaSender,
                    mediaItemClass = mediaItem,
                    messageTypeClass = messageType,
                )

            val writeType =
                multiSend.parameterTypes[5].also { derived ->
                    check(derived.isEnum) {
                        "WriteType derived from multiSend param[5] is not an enum: ${derived.name}"
                    }
                    Log.i(TAG, "WriteType derived from multiSend signature: ${derived.name}")
                }

            val listener =
                multiSend.parameterTypes[8].also { derived ->
                    check(derived.isInterface) {
                        "Listener derived from multiSend param[8] is not an interface: ${derived.name}"
                    }
                    Log.i(TAG, "Listener derived from multiSend signature: ${derived.name}")
                }

            val mediaItemCtor =
                mediaItem.getConstructor(
                    String::class.java,
                    Long::class.javaPrimitiveType,
                )

            val masterDbField =
                selectFieldCandidate(
                    label = "MasterDatabase singleton field on ${masterDb.name}",
                    candidates =
                        masterDb.declaredFields.filter { field ->
                            Modifier.isStatic(field.modifiers) && field.type == masterDb
                        },
                ).apply { isAccessible = true }

            val roomDao =
                selectMethodCandidate(
                    label = "MasterDatabase roomDao accessor on ${masterDb.name}",
                    candidates =
                        masterDb.methods.filter { m ->
                            !Modifier.isStatic(m.modifiers) &&
                                m.parameterCount == 0 &&
                                m.returnType != Void.TYPE &&
                                m.returnType != masterDb &&
                                m.returnType != Any::class.java &&
                                m.returnType.methods.any { daoMethod ->
                                    !Modifier.isStatic(daoMethod.modifiers) &&
                                        daoMethod.parameterTypes.contentEquals(arrayOf(Long::class.javaPrimitiveType)) &&
                                        !daoMethod.returnType.isPrimitive &&
                                        daoMethod.returnType != Void.TYPE &&
                                        daoMethod.returnType != Any::class.java &&
                                        daoMethod.returnType != java.lang.Integer::class.java &&
                                        daoMethod.returnType != java.lang.Long::class.java &&
                                        daoMethod.returnType != java.lang.Boolean::class.java &&
                                        daoMethod.returnType != String::class.java
                                }
                        },
                    preferredNames = setOf("O"),
                )

            val daoClass = roomDao.returnType
            val entityLookup =
                selectMethodCandidate(
                    label = "RoomDao entity lookup on ${daoClass.name}",
                    candidates =
                        daoClass.methods.filter { m ->
                            !Modifier.isStatic(m.modifiers) &&
                                m.parameterTypes.contentEquals(arrayOf(Long::class.javaPrimitiveType)) &&
                                !m.returnType.isPrimitive &&
                                m.returnType != Void.TYPE &&
                                m.returnType != daoClass &&
                                m.returnType != Any::class.java &&
                                m.returnType != java.lang.Integer::class.java &&
                                m.returnType != java.lang.Long::class.java &&
                                m.returnType != java.lang.Boolean::class.java &&
                                m.returnType != String::class.java
                        },
                    preferredNames = setOf("h"),
                )

            val photoConst = requireEnumConstant(messageType, "Photo")
            val multiPhotoConst = requireEnumConstant(messageType, "MultiPhoto")
            val writeNoneConst = requireEnumConstant(writeType, "None")

            Log.i(
                TAG,
                "KakaoClassRegistry.discover complete — " +
                    "ChatMediaSender=${chatMediaSender.name}, " +
                    "MessageType=${messageType.name}, " +
                    "WriteType=${writeType.name}, " +
                    "Listener=${listener.name}, " +
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

        internal fun selectMethodCandidateForTest(
            label: String,
            candidates: List<Method>,
            preferredNames: Set<String> = emptySet(),
        ): Method = selectMethodCandidate(label, candidates, preferredNames)

        internal fun selectChatMediaSenderCandidateForTest(
            candidates: List<Class<*>>,
            mediaItemClass: Class<*>,
            function0Class: Class<*>,
            function1Class: Class<*>,
        ): Class<*> =
            selectClassCandidate(
                label = "ChatMediaSender",
                candidates =
                    candidates.filter { candidate ->
                        matchesChatMediaSenderClass(
                            clazz = candidate,
                            mediaItemClass = mediaItemClass,
                            function0Class = function0Class,
                            function1Class = function1Class,
                        )
                    },
                preferredNames = emptySet(),
            )

        internal fun resolveChatMediaSenderMethodsForTest(
            chatMediaSenderClass: Class<*>,
            mediaItemClass: Class<*>,
            messageTypeClass: Class<*>,
        ): Pair<Method, Method> =
            resolveChatMediaSendMethods(
                chatMediaSenderClass = chatMediaSenderClass,
                mediaItemClass = mediaItemClass,
                messageTypeClass = messageTypeClass,
            )

        private fun discoverClass(
            classLoader: ClassLoader,
            scanner: DexClassScanner,
            lastKnownNames: Array<String>,
            label: String,
            signatureMatcher: (Class<*>) -> Boolean,
        ): Class<*> {
            val knownMatches =
                lastKnownNames.mapNotNull { name ->
                    runCatching {
                        Class.forName(name, false, classLoader)
                    }.getOrNull()?.takeIf(signatureMatcher)
                }
            val knownConcrete = knownMatches.filter(::isConcreteClass).distinctBy { clazz -> clazz.name }
            if (knownConcrete.size == 1) {
                val selected = knownConcrete.single()
                Log.i(TAG, "$label found at known concrete name: ${selected.name}")
                return selected
            }
            for (name in lastKnownNames) {
                val clazz =
                    runCatching {
                        Class.forName(name, false, classLoader)
                    }.getOrNull()
                if (clazz != null && signatureMatcher(clazz)) {
                    Log.i(TAG, "$label matched known name candidate: $name")
                }
            }
            if (knownMatches.isEmpty()) {
                Log.w(TAG, "$label not found at known names ${lastKnownNames.toList()}, starting DEX scan")
            } else {
                Log.w(
                    TAG,
                    "$label known-name candidates were insufficient ${knownMatches.map { candidate -> candidate.name }}, starting DEX scan",
                )
            }
            val scannedMatches = scanner.findAll(signatureMatcher)
            return selectClassCandidate(
                label = label,
                candidates = (knownMatches + scannedMatches).distinctBy { clazz -> clazz.name },
                preferredNames = lastKnownNames.toSet(),
            )
        }

        private fun isConcreteClass(clazz: Class<*>): Boolean = !Modifier.isAbstract(clazz.modifiers) && !clazz.isInterface

        private fun matchesChatMediaSenderClass(
            clazz: Class<*>,
            mediaItemClass: Class<*>,
            function0Class: Class<*>,
            function1Class: Class<*>,
        ): Boolean =
            isConcreteClass(clazz) &&
                clazz.constructors.any { ctor ->
                    ctor.parameterTypes.size == 4 &&
                        ctor.parameterTypes[1] == java.lang.Long::class.java &&
                        ctor.parameterTypes[2] == function0Class &&
                        ctor.parameterTypes[3] == function1Class
                } &&
                methodsInHierarchy(clazz).any { method ->
                    !Modifier.isStatic(method.modifiers) &&
                        method.parameterTypes.size == 2 &&
                        method.parameterTypes[0] == mediaItemClass &&
                        method.parameterTypes[1] == Boolean::class.javaPrimitiveType
                }

        private fun resolveChatMediaSendMethods(
            chatMediaSenderClass: Class<*>,
            mediaItemClass: Class<*>,
            messageTypeClass: Class<*>,
        ): Pair<Method, Method> {
            val singleSend =
                selectMethodCandidate(
                    label = "ChatMediaSender single send on ${chatMediaSenderClass.name}",
                    candidates =
                        methodsInHierarchy(chatMediaSenderClass).filter { method ->
                            !Modifier.isStatic(method.modifiers) &&
                                method.parameterTypes.size == 2 &&
                                method.parameterTypes[0] == mediaItemClass &&
                                method.parameterTypes[1] == Boolean::class.javaPrimitiveType
                        },
                    preferredNames = setOf("n"),
                )
            val multiSend =
                selectMethodCandidate(
                    label = "ChatMediaSender multi send on ${chatMediaSenderClass.name}",
                    candidates =
                        methodsInHierarchy(chatMediaSenderClass).filter { method ->
                            !Modifier.isStatic(method.modifiers) &&
                                method.parameterCount == 9 &&
                                method.parameterTypes[0] == List::class.java &&
                                method.parameterTypes[1] == messageTypeClass
                        },
                    preferredNames = setOf("p"),
                )
            return singleSend to multiSend
        }

        private fun methodsInHierarchy(clazz: Class<*>): List<Method> {
            val methods = mutableListOf<Method>()
            var current: Class<*>? = clazz
            while (current != null && current != Any::class.java) {
                current.declaredMethods.forEach { method ->
                    runCatching { method.isAccessible = true }
                    methods += method
                }
                current = current.superclass
            }
            return methods
        }

        private fun selectClassCandidate(
            label: String,
            candidates: List<Class<*>>,
            preferredNames: Set<String>,
        ): Class<*> {
            val uniqueCandidates = candidates.distinctBy { clazz -> clazz.name }
            check(uniqueCandidates.isNotEmpty()) {
                "$label not found by signature"
            }
            val preferredKnownConcrete =
                uniqueCandidates.filter { candidate ->
                    candidate.name in preferredNames && isConcreteClass(candidate)
                }
            if (preferredKnownConcrete.size == 1) return preferredKnownConcrete.single()

            val preferredConcrete = uniqueCandidates.filter(::isConcreteClass)
            if (preferredConcrete.size == 1) return preferredConcrete.single()

            val preferredKnown = uniqueCandidates.filter { candidate -> candidate.name in preferredNames }
            if (preferredKnown.size == 1) return preferredKnown.single()

            val ambiguousCandidates =
                when {
                    preferredKnownConcrete.isNotEmpty() -> preferredKnownConcrete
                    preferredConcrete.isNotEmpty() -> preferredConcrete
                    preferredKnown.isNotEmpty() -> preferredKnown
                    else -> uniqueCandidates
                }
            check(ambiguousCandidates.size == 1) {
                "$label is ambiguous: ${ambiguousCandidates.joinToString { candidate -> candidate.name }}"
            }
            return ambiguousCandidates.single()
        }

        private fun selectMethodCandidate(
            label: String,
            candidates: List<Method>,
            preferredNames: Set<String> = emptySet(),
        ): Method {
            val uniqueCandidates = candidates.distinctBy(::methodSignature)
            val preferredCandidates =
                uniqueCandidates.filter { method -> method.name in preferredNames }
            val narrowedCandidates = if (preferredCandidates.isNotEmpty()) preferredCandidates else uniqueCandidates
            return chooseUniqueCandidate(
                label = label,
                candidates = narrowedCandidates,
                preference = { method ->
                    !Modifier.isAbstract(method.modifiers) && !method.isBridge && !method.isSynthetic
                },
                describe = ::methodSignature,
            )
        }

        private fun selectFieldCandidate(
            label: String,
            candidates: List<Field>,
        ): Field =
            chooseUniqueCandidate(
                label = label,
                candidates = candidates.distinctBy { field -> "${field.declaringClass.name}.${field.name}:${field.type.name}" },
                preference = { field -> !field.isSynthetic },
                describe = { field -> "${field.declaringClass.name}.${field.name}:${field.type.name}" },
            )

        private fun <T> chooseUniqueCandidate(
            label: String,
            candidates: List<T>,
            preference: (T) -> Boolean,
            describe: (T) -> String,
        ): T {
            check(candidates.isNotEmpty()) { "$label not found" }
            val preferred = candidates.filter(preference).ifEmpty { candidates }
            check(preferred.size == 1) {
                "$label is ambiguous: ${preferred.joinToString { candidate -> describe(candidate) }}"
            }
            return preferred.single()
        }

        private fun methodSignature(method: Method): String =
            method.parameterTypes.joinToString(
                prefix = "${method.declaringClass.name}.${method.name}(",
                postfix = "):${method.returnType.name}",
            ) { parameterType -> parameterType.name }

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
