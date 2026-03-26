package party.qwer.iris

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.ArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal interface NativeImageReplySender {
    fun send(
        roomId: Long,
        uris: List<Uri>,
        threadId: Long?,
        threadScope: Int?,
    )
}

internal class KakaoNativeImageReplySender(
    private val runtime: KakaoNativeRuntime = ReflectionKakaoNativeRuntime,
) : NativeImageReplySender {
    override fun send(
        roomId: Long,
        uris: List<Uri>,
        threadId: Long?,
        threadScope: Int?,
    ) {
        runtime.sendImages(roomId, uris, threadId, threadScope)
    }
}

internal interface KakaoNativeRuntime {
    fun sendImages(
        roomId: Long,
        uris: List<Uri>,
        threadId: Long?,
        threadScope: Int?,
    )
}

@Deprecated("Superseded by LSPosed bridge module — caller identity requires in-process execution")
internal object ReflectionKakaoNativeRuntime : KakaoNativeRuntime {
    private const val KAKAO_PACKAGE = "com.kakao.talk"
    private const val PHOTO_QUALITY_UNKNOWN = 0

    private val lock = Any()
    private var state: BootstrapState? = null
    private val mainLooperLock = Any()
    private var preparedMainLooper: Looper? = null

    override fun sendImages(
        roomId: Long,
        uris: List<Uri>,
        threadId: Long?,
        threadScope: Int?,
    ) {
        runOnMainLooper {
            require(uris.isNotEmpty()) { "no prepared image URIs" }
            IrisLogger.info("[KakaoNativeImageReplySender] sendImages start room=$roomId uris=${uris.size} threadId=$threadId scope=$threadScope")
            val bootstrap = ensureBootstrap()
            withContextClassLoader(bootstrap.loader) {
                val chatRoom = resolveChatRoom(bootstrap, roomId) ?: error("chat room not found: $roomId")
                if (uris.size == 1) {
                    IrisLogger.info("[KakaoNativeImageReplySender] using direct single-item ChatSendingLog path")
                    val sendingLog = buildNativeSinglePhotoSendingLog(bootstrap, roomId, uris.first(), threadId, threadScope)
                    sendChatLog(bootstrap, chatRoom, sendingLog)
                } else {
                    IrisLogger.info("[KakaoNativeImageReplySender] using ChatMediaSender multi-uri path")
                    sendWithChatMediaSender(bootstrap, chatRoom, uris, threadId, threadScope)
                }
                IrisLogger.info("[KakaoNativeImageReplySender] sendImages finished room=$roomId")
            }
        }
    }

    private fun ensureBootstrap(): BootstrapState {
        state?.let { return it }
        synchronized(lock) {
            state?.let { return it }

            val activityThread = resolveActivityThread()
            val systemContext = resolveSystemContext(activityThread)
            val packageContext =
                systemContext.createPackageContext(
                    KAKAO_PACKAGE,
                    Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY,
                )
            val loader = packageContext.classLoader ?: error("Kakao package context has no classloader")

            val bootstrap =
                withContextClassLoader(loader) {
                    val appContext = ensureAppContext(activityThread, systemContext, packageContext, loader)
                    ensureAppHolder(appContext, loader)
                    seedHardwareContext(loader, systemContext)
                    IrisLogger.info("[KakaoNativeImageReplySender] bootstrap appContext ready")
                    IrisLogger.info(
                        "[KakaoNativeImageReplySender] bootstrap appContext class=${appContext.javaClass.name} applicationContext=${runCatching { appContext.applicationContext?.javaClass?.name }.getOrNull()}",
                    )
                    IrisLogger.info("[KakaoNativeImageReplySender] bootstrap core init start")
                    ensureCoreInitialized(appContext, loader)
                    IrisLogger.info("[KakaoNativeImageReplySender] bootstrap core init done")
                    IrisLogger.info("[KakaoNativeImageReplySender] bootstrap loco init start")
                    ensureLocoInitialized(appContext, loader)
                    IrisLogger.info("[KakaoNativeImageReplySender] bootstrap loco init done")
                    IrisLogger.info("[KakaoNativeImageReplySender] bootstrap master-db init start")
                    val masterDatabase = ensureMasterDatabaseInitialized(appContext, loader)
                    IrisLogger.info("[KakaoNativeImageReplySender] bootstrap master-db init done")
                    IrisLogger.info("[KakaoNativeImageReplySender] bootstrap ready loader=${loader.javaClass.name}")
                    BootstrapState(
                        appContext = appContext,
                        loader = loader,
                        masterDatabase = masterDatabase,
                    )
                }

            state = bootstrap
            return bootstrap
        }
    }

    private fun ensureAppContext(
        activityThread: Any,
        systemContext: Context,
        packageContext: Context,
        @Suppress("UNUSED_PARAMETER") loader: ClassLoader,
    ): Context {
        runCatching {
            val loadedApk = resolveLoadedApk(activityThread, systemContext)
            val instrumentationClass = Class.forName("android.app.Instrumentation")
            val app =
                loadedApk.javaClass
                    .getDeclaredMethod(
                        "makeApplication",
                        Boolean::class.javaPrimitiveType,
                        instrumentationClass,
                    ).apply { isAccessible = true }
                    .invoke(loadedApk, false, null) as? Context
                    ?: error("LoadedApk.makeApplication returned null")
            IrisLogger.info("[KakaoNativeImageReplySender] using LoadedApk.makeApplication appContext=${app.javaClass.name}")
            return app
        }.onFailure {
            IrisLogger.error("[KakaoNativeImageReplySender] LoadedApk.makeApplication failed: ${it.message}", it)
        }

        return object : ContextWrapper(packageContext) {
            override fun getApplicationContext(): Context = this
        }
    }

    private fun ensureAppHolder(
        appContext: Context,
        loader: ClassLoader,
    ) {
        val holderClass = loadClass(loader, "dm.m")

        @Suppress("UNCHECKED_CAST")
        val holder = holderClass.getMethod("a").invoke(null) as AtomicReference<Any?>
        if (holder.get() != null) {
            return
        }
        val appClass = loadClass(loader, "com.kakao.talk.application.App")
        val app = appClass.getDeclaredConstructor().newInstance()
        val baseField = ContextWrapper::class.java.getDeclaredField("mBase").apply { isAccessible = true }
        baseField.set(app, appContext)
        IrisLogger.info("[KakaoNativeImageReplySender] App holder created with ContextWrapper.mBase injection")
        holder.set(app)
    }

    private fun seedHardwareContext(
        loader: ClassLoader,
        systemContext: Context,
    ) {
        runCatching {
            val hardwareClass = loadClass(loader, "IZ.V")
            val lazyField =
                hardwareClass.declaredFields.firstOrNull { field ->
                    Modifier.isStatic(field.modifiers) &&
                        field.type.name == "kotlin.Lazy" &&
                        field.name.contains("context", ignoreCase = true)
                } ?: error("IZ.V context lazy field not found")
            lazyField.isAccessible = true
            val lazyObject = lazyField.get(null) ?: error("IZ.V context lazy instance is null")
            val valueField =
                lazyObject.javaClass.declaredFields.firstOrNull { field ->
                    field.type == Any::class.java && field.name.contains("value", ignoreCase = true)
                } ?: error("IZ.V lazy backing value field not found")
            valueField.isAccessible = true
            valueField.set(lazyObject, systemContext)
            IrisLogger.info("[KakaoNativeImageReplySender] seeded IZ.V context lazy with systemContext")
        }.onFailure {
            IrisLogger.error("[KakaoNativeImageReplySender] failed to seed IZ.V context lazy: ${it.message}", it)
        }
    }

    private fun ensureCoreInitialized(
        packageContext: Context,
        loader: ClassLoader,
    ) {
        val initializerClass = loadClass(loader, "com.kakao.talk.core.CoreInitializer")
        val initializer = initializerClass.getDeclaredConstructor().newInstance()
        initializerClass.getMethod("create", Context::class.java).invoke(initializer, packageContext)
    }

    private fun ensureLocoInitialized(
        packageContext: Context,
        loader: ClassLoader,
    ) {
        val initializerClass = loadClass(loader, "com.kakao.talk.core.loco.LocoInitializer")
        val initializer = initializerClass.getDeclaredConstructor().newInstance()
        initializerClass.getMethod("create", Context::class.java).invoke(initializer, packageContext)
    }

    private fun ensureMasterDatabaseInitialized(
        packageContext: Context,
        loader: ClassLoader,
    ): Any {
        val masterDatabaseClass = loadClass(loader, "com.kakao.talk.database.MasterDatabase")
        val instanceField =
            masterDatabaseClass.declaredFields
                .firstOrNull {
                    Modifier.isStatic(it.modifiers) && it.type == masterDatabaseClass
                }?.apply { isAccessible = true }
                ?: error("MasterDatabase instance field not found")
        instanceField.get(null)?.let { return it }
        val companion =
            masterDatabaseClass.declaredFields
                .firstOrNull {
                    Modifier.isStatic(it.modifiers) && it.type.name.contains("MasterDatabase$")
                }?.apply { isAccessible = true }
                ?.get(null)
                ?: error("MasterDatabase companion field not found")
        val buildMethod =
            companion.javaClass.methods.firstOrNull { method ->
                method.parameterTypes.contentEquals(arrayOf(Context::class.java)) &&
                    masterDatabaseClass.isAssignableFrom(method.returnType)
            } ?: error("MasterDatabase build(Context) method not found")
        IrisLogger.info(
            "[KakaoNativeImageReplySender] MasterDatabase build context=${packageContext.javaClass.name} applicationContext=${runCatching { packageContext.applicationContext?.javaClass?.name }.getOrNull()}",
        )
        val db = buildMethod.invoke(companion, packageContext) ?: error("MasterDatabase build(Context) returned null")
        instanceField.set(null, db)
        return db
    }

    private fun resolveChatRoom(
        bootstrap: BootstrapState,
        roomId: Long,
    ): Any? {
        runCatching {
            val roomDao =
                bootstrap.masterDatabase.javaClass
                    .getMethod("O")
                    .invoke(bootstrap.masterDatabase)
            val entity = roomDao.javaClass.getMethod("h", Long::class.javaPrimitiveType).invoke(roomDao, roomId)
            if (entity != null) {
                val chatRoomClass = loadClass(bootstrap.loader, "hp.t")
                val companion =
                    chatRoomClass.declaredFields
                        .asSequence()
                        .filter { Modifier.isStatic(it.modifiers) }
                        .mapNotNull { field ->
                            runCatching {
                                field.isAccessible = true
                                field.get(null)
                            }.getOrNull()
                        }.firstOrNull { candidate ->
                            candidate.javaClass.methods.any { method ->
                                method.name == "c" &&
                                    method.parameterCount == 1 &&
                                    chatRoomClass.isAssignableFrom(method.returnType) &&
                                    method.parameterTypes[0].isAssignableFrom(entity.javaClass)
                            }
                        }
                if (companion != null) {
                    val resolver =
                        companion.javaClass.methods.first { method ->
                            method.name == "c" &&
                                method.parameterCount == 1 &&
                                chatRoomClass.isAssignableFrom(method.returnType) &&
                                method.parameterTypes[0].isAssignableFrom(entity.javaClass)
                        }
                    return resolver.invoke(companion, entity)
                }
                val ctor =
                    chatRoomClass.declaredConstructors.firstOrNull { constructor ->
                        constructor.parameterTypes.size == 1 &&
                            constructor.parameterTypes[0].isAssignableFrom(entity.javaClass)
                    } ?: error("hp.t companion/constructor resolver not found")
                ctor.isAccessible = true
                return ctor.newInstance(entity)
            }
        }.onFailure { IrisLogger.error("[KakaoNativeImageReplySender] direct roomDao resolver failed: ${it.message}", it) }

        runCatching {
            val managerClass = loadClass(bootstrap.loader, "hp.J0")
            val companion =
                managerClass.declaredFields
                    .asSequence()
                    .filter { Modifier.isStatic(it.modifiers) }
                    .mapNotNull { field ->
                        runCatching {
                            field.isAccessible = true
                            field.get(null)
                        }.getOrNull()
                    }.firstOrNull { candidate ->
                        candidate.javaClass.methods.any { method ->
                            method.name == "j" &&
                                method.parameterCount == 0 &&
                                method.returnType == managerClass
                        }
                    }
            val manager =
                companion
                    ?.javaClass
                    ?.methods
                    ?.firstOrNull { method ->
                        method.name == "j" &&
                            method.parameterCount == 0 &&
                            method.returnType == managerClass
                    }?.invoke(companion)
                    ?: managerClass.methods
                        .firstOrNull { method ->
                            Modifier.isStatic(method.modifiers) &&
                                method.parameterCount == 0 &&
                                method.returnType == managerClass
                        }?.invoke(null)
                    ?: error("hp.J0 singleton accessor not found")
            val broadResolver =
                manager.javaClass.getMethod(
                    "e0",
                    Long::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType,
                )
            val chatRoom = broadResolver.invoke(manager, roomId, true, true)
            if (chatRoom != null) {
                return chatRoom
            }
            val resolver = manager.javaClass.getMethod("d0", Long::class.javaPrimitiveType)
            return resolver.invoke(manager, roomId)
        }.onFailure { IrisLogger.error("[KakaoNativeImageReplySender] hp.J0 resolver failed: ${it.message}", it) }

        runCatching {
            val helperClass = loadClass(bootstrap.loader, "hp.m0")
            val helper =
                helperClass.declaredFields
                    .firstOrNull {
                        Modifier.isStatic(it.modifiers) && it.type == helperClass
                    }?.apply { isAccessible = true }
                    ?.get(null)
                    ?: error("hp.m0 singleton field not found")
            val resolver = helperClass.getMethod("o", Long::class.javaPrimitiveType)
            return resolver.invoke(helper, roomId)
        }.onFailure { IrisLogger.error("[KakaoNativeImageReplySender] hp.m0 resolver failed: ${it.message}", it) }

        runCatching {
            val helperClass = loadClass(bootstrap.loader, "ry0.C2706m0")
            val companion = helperClass.getDeclaredField("a").get(null)
            val resolver = helperClass.getMethod("o", Long::class.javaPrimitiveType)
            return resolver.invoke(companion, roomId)
        }.onFailure { IrisLogger.error("[KakaoNativeImageReplySender] ry0.C2706m0 resolver failed: ${it.message}", it) }

        runCatching {
            val legacyHelperClass = loadClass(bootstrap.loader, "ry0.C2603J0")
            val companion = legacyHelperClass.getDeclaredField("INSTANCE").get(null)
            val manager = legacyHelperClass.getMethod("j").invoke(companion)
            val resolver = manager.javaClass.getMethod("d0", Long::class.javaPrimitiveType)
            return resolver.invoke(manager, roomId)
        }.onFailure { IrisLogger.error("[KakaoNativeImageReplySender] ry0.C2603J0 resolver failed: ${it.message}", it) }

        return null
    }

    private fun sendSingleWithChatMediaSender(
        bootstrap: BootstrapState,
        chatRoom: Any,
        uri: Uri,
        threadId: Long?,
        threadScope: Int?,
    ) {
        val loader = bootstrap.loader
        val mediaSenderClass = loadClass(loader, "bh.c")
        val mediaItemClass = loadClass(loader, "com.kakao.talk.model.media.MediaItem")
        val function0Class = loadClass(loader, "kotlin.jvm.functions.Function0")
        val function1Class = loadClass(loader, "kotlin.jvm.functions.Function1")
        val sendWithChatRoomInThread = threadId != null && threadScope == 3
        val rawPath = uri.path ?: uri.toString()

        val sendWithThreadProxy =
            Proxy.newProxyInstance(loader, arrayOf(function0Class)) { _, method, _ ->
                when (method.name) {
                    "invoke" -> sendWithChatRoomInThread
                    "toString" -> "IrisSendWithChatRoomInThread($sendWithChatRoomInThread)"
                    "hashCode" -> sendWithChatRoomInThread.hashCode()
                    "equals" -> false
                    else -> null
                }
            }
        val attachmentDecoratorProxy =
            Proxy.newProxyInstance(loader, arrayOf(function1Class)) { _, method, args ->
                when (method.name) {
                    "invoke" -> args?.getOrNull(0) as? JSONObject
                    "toString" -> "IrisAttachmentDecorator"
                    "hashCode" -> 0
                    "equals" -> false
                    else -> null
                }
            }
        val sender =
            mediaSenderClass
                .getConstructor(chatRoom.javaClass, java.lang.Long::class.java, function0Class, function1Class)
                .newInstance(chatRoom, threadId, sendWithThreadProxy, attachmentDecoratorProxy)
        val mediaItem =
            mediaItemClass
                .getConstructor(String::class.java, Long::class.javaPrimitiveType)
                .newInstance(rawPath, 0L)
        IrisLogger.info("[KakaoNativeImageReplySender] invoking ChatMediaSender.n path=$rawPath")
        mediaSenderClass
            .getMethod("n", mediaItemClass, Boolean::class.javaPrimitiveType)
            .invoke(sender, mediaItem, false)
        IrisLogger.info("[KakaoNativeImageReplySender] ChatMediaSender.n returned")
    }

    private fun buildNativeSinglePhotoSendingLog(
        bootstrap: BootstrapState,
        roomId: Long,
        uri: Uri,
        threadId: Long?,
        threadScope: Int?,
    ): Any {
        val loader = bootstrap.loader
        val path = uri.path ?: error("single photo uri has no path: $uri")
        val imageFile = File(path)
        val typeClass = loadChatMessageTypeClass(loader)
        val builderClass = loadClass(loader, "com.kakao.talk.manager.send.sending.ChatSendingLog\$b")
        val qualityClass = loadClass(loader, "RL.c")
        val originClass = loadClass(loader, "com.kakao.talk.activity.chatroom.c")
        val fileItemClass = loadClass(loader, "com.kakao.talk.model.media.FileItem")
        val type = enumConstant(typeClass, "Photo")
        val builder =
            builderClass
                .getConstructor(
                    Long::class.javaPrimitiveType,
                    typeClass,
                    Int::class.javaPrimitiveType,
                    java.lang.Long::class.java,
                    Boolean::class.javaPrimitiveType,
                ).newInstance(
                    roomId,
                    type,
                    resolveScope(threadId, threadScope),
                    threadId,
                    false,
                )
        val attachment = JSONObject().apply { put("cmt", "") }
        val fileItem =
            fileItemClass
                .getConstructor(String::class.java)
                .newInstance(path)

        builderClass.getMethod("c", JSONObject::class.java).invoke(builder, attachment)
        builderClass.getMethod("e", fileItemClass).invoke(builder, fileItem)
        builderClass.getMethod("i", qualityClass).invoke(builder, enumConstant(qualityClass, "LOW"))
        builderClass.getMethod("h", Boolean::class.javaPrimitiveType).invoke(builder, false)
        builderClass.getMethod("l", Class::class.java, String::class.java).invoke(builder, originClass, "II")
        val sendingLog = builderClass.getMethod("b").invoke(builder) ?: error("failed to build native single-photo ChatSendingLog")
        val jvField =
            sendingLog.javaClass.declaredFields.firstOrNull { field ->
                field.type.name == "com.kakao.talk.manager.send.sending.ChatSendingLog\$g"
            } ?: error("ChatSendingLog jv field not found")
        jvField.isAccessible = true
        val jv = jvField.get(sendingLog)
        jv.javaClass.getMethod("J", Uri::class.java).invoke(jv, uri)

        val options =
            BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
        BitmapFactory.decodeFile(path, options)
        if (options.outWidth > 0 && options.outHeight > 0) {
            sendingLog.javaClass
                .getMethod("B1", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(sendingLog, options.outWidth, options.outHeight)
        }

        val uploadUriField =
            sendingLog.javaClass.declaredFields.firstOrNull { field ->
                field.type == Uri::class.java
            } ?: error("ChatSendingLog upload Uri field not found")
        uploadUriField.isAccessible = true
        uploadUriField.set(sendingLog, uri)

        val backingFileField =
            sendingLog.javaClass.declaredFields.firstOrNull { field ->
                field.type == File::class.java
            } ?: error("ChatSendingLog backing File field not found")
        backingFileField.isAccessible = true
        backingFileField.set(sendingLog, imageFile)

        IrisLogger.info("[KakaoNativeImageReplySender] built native single-photo ChatSendingLog path=$path")
        return sendingLog
    }

    private fun sendWithChatMediaSender(
        bootstrap: BootstrapState,
        chatRoom: Any,
        uris: List<Uri>,
        threadId: Long?,
        threadScope: Int?,
    ) {
        val loader = bootstrap.loader
        val mediaSenderClass = loadClass(loader, "bh.c")
        val typeClass = loadChatMessageTypeClass(loader)
        val writeTypeClass = loadClass(loader, "com.kakao.talk.manager.send.ChatSendingLogRequest\$c")
        val listenerClass = loadClass(loader, "com.kakao.talk.manager.send.m")
        val function0Class = loadClass(loader, "kotlin.jvm.functions.Function0")
        val function1Class = loadClass(loader, "kotlin.jvm.functions.Function1")
        val sendWithChatRoomInThread = threadId != null && threadScope == 3

        val sendWithThreadProxy =
            Proxy.newProxyInstance(loader, arrayOf(function0Class)) { _, method, _ ->
                when (method.name) {
                    "invoke" -> sendWithChatRoomInThread
                    "toString" -> "IrisSendWithChatRoomInThread($sendWithChatRoomInThread)"
                    "hashCode" -> sendWithChatRoomInThread.hashCode()
                    "equals" -> false
                    else -> null
                }
            }
        val attachmentDecoratorProxy =
            Proxy.newProxyInstance(loader, arrayOf(function1Class)) { _, method, args ->
                when (method.name) {
                    "invoke" -> args?.getOrNull(0) as? JSONObject
                    "toString" -> "IrisAttachmentDecorator"
                    "hashCode" -> 0
                    "equals" -> false
                    else -> null
                }
            }

        val sender =
            mediaSenderClass
                .getConstructor(chatRoom.javaClass, java.lang.Long::class.java, function0Class, function1Class)
                .newInstance(chatRoom, threadId, sendWithThreadProxy, attachmentDecoratorProxy)
        val type = enumConstant(typeClass, if (uris.size == 1) "Photo" else "MultiPhoto")
        val writeTypeNone = enumConstant(writeTypeClass, "None")
        val sendMethod =
            mediaSenderClass.getMethod(
                "p",
                List::class.java,
                typeClass,
                String::class.java,
                JSONObject::class.java,
                JSONObject::class.java,
                writeTypeClass,
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                listenerClass,
            )
        IrisLogger.info("[KakaoNativeImageReplySender] invoking ChatMediaSender.p uriCount=${uris.size}")
        sendMethod.invoke(sender, ArrayList(uris), type, null, null, null, writeTypeNone, false, false, null)
        IrisLogger.info("[KakaoNativeImageReplySender] ChatMediaSender.p returned")
    }

    private fun buildSingleUriSendingLog(
        bootstrap: BootstrapState,
        roomId: Long,
        uri: Uri,
        threadId: Long?,
        threadScope: Int?,
    ): Any {
        val loader = bootstrap.loader
        val typeClass = loadChatMessageTypeClass(loader)
        val builderClass = loadClass(loader, "com.kakao.talk.manager.send.sending.ChatSendingLog\$b")
        val qualityClass = loadClass(loader, "RL.c")
        val originClass = loadClass(loader, "com.kakao.talk.activity.chatroom.c")
        val type = enumConstant(typeClass, "Photo")
        val builder =
            builderClass
                .getConstructor(
                    Long::class.javaPrimitiveType,
                    typeClass,
                    Int::class.javaPrimitiveType,
                    java.lang.Long::class.java,
                    Boolean::class.javaPrimitiveType,
                ).newInstance(
                    roomId,
                    type,
                    resolveScope(threadId, threadScope),
                    threadId,
                    false,
                )

        builderClass.getMethod("d", Uri::class.java).invoke(builder, uri)
        builderClass.getMethod("r", qualityClass).invoke(builder, enumConstant(qualityClass, "LOW"))
        builderClass.getMethod("l", Class::class.java, String::class.java).invoke(builder, originClass, "MD")
        val sendingLog = builderClass.getMethod("b").invoke(builder) ?: error("failed to build single-uri ChatSendingLog")
        sendingLog.javaClass.getMethod("T1").invoke(sendingLog)
        IrisLogger.info("[KakaoNativeImageReplySender] built single-uri ChatSendingLog for uri=$uri")
        return sendingLog
    }

    private fun buildSendingLog(
        bootstrap: BootstrapState,
        roomId: Long,
        uris: List<Uri>,
        threadId: Long?,
        threadScope: Int?,
    ): Any {
        val loader = bootstrap.loader
        val typeClass = loadChatMessageTypeClass(loader)
        val builderClass = loadClass(loader, "com.kakao.talk.manager.send.sending.ChatSendingLog\$b")
        val photoClass = loadClass(loader, "com.kakao.talk.manager.send.sending.ChatSendingLog\$SendingPhoto")
        val originClass = loadClass(loader, "com.kakao.talk.activity.chatroom.c")

        val type = enumConstant(typeClass, if (uris.size == 1) "Photo" else "MultiPhoto")
        val builder =
            builderClass
                .getConstructor(
                    Long::class.javaPrimitiveType,
                    typeClass,
                    Int::class.javaPrimitiveType,
                    java.lang.Long::class.java,
                    Boolean::class.javaPrimitiveType,
                ).newInstance(
                    roomId,
                    type,
                    resolveScope(threadId, threadScope),
                    threadId,
                    false,
                )

        val addPhoto = builderClass.getMethod("a", photoClass)
        val setMessage = builderClass.getMethod("j", String::class.java)
        val setOrigin = builderClass.getMethod("l", Class::class.java, String::class.java)
        val build = builderClass.getMethod("b")
        val photoCtor =
            photoClass.getConstructor(
                String::class.java,
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                String::class.java,
                String::class.java,
            )

        uris.forEach { uri ->
            val sendingPhoto =
                photoCtor.newInstance(
                    uri.toString(),
                    PHOTO_QUALITY_UNKNOWN,
                    false,
                    null,
                    "",
                )
            addPhoto.invoke(builder, sendingPhoto)
        }
        setMessage.invoke(builder, "")
        setOrigin.invoke(builder, originClass, if (uris.size == 1) "II" else "MD")
        return build.invoke(builder) ?: error("failed to build ChatSendingLog")
    }

    private fun sendChatLog(
        bootstrap: BootstrapState,
        chatRoom: Any,
        sendingLog: Any,
    ) {
        val loader = bootstrap.loader
        val requestClass = loadClass(loader, "com.kakao.talk.manager.send.ChatSendingLogRequest")
        val writeTypeClass = loadClass(loader, "com.kakao.talk.manager.send.ChatSendingLogRequest\$c")
        val listenerClass = loadClass(loader, "com.kakao.talk.manager.send.m")
        val writeTypeNone = enumConstant(writeTypeClass, "None")
        val sendingLogManagerClass = loadClass(loader, "com.kakao.talk.manager.send.sending.b")
        val sendingLogManager =
            sendingLogManagerClass.declaredFields
                .asSequence()
                .filter { Modifier.isStatic(it.modifiers) }
                .mapNotNull { field ->
                    runCatching {
                        field.isAccessible = true
                        field.get(null)
                    }.getOrNull()
                }.firstOrNull { candidate ->
                    sendingLogManagerClass.isInstance(candidate)
                } ?: error("ChatSendingLogManager singleton not found")
        runCatching {
            sendingLog.javaClass
                .getMethod("w1", Boolean::class.javaPrimitiveType)
                .invoke(sendingLog, false)
            IrisLogger.info("[KakaoNativeImageReplySender] primed ChatSendingLog retry/send state via w1(false)")
        }.onFailure {
            IrisLogger.error("[KakaoNativeImageReplySender] ChatSendingLog.w1(false) failed: ${it.message}", it)
        }
        runCatching {
            sendingLogManagerClass
                .methods
                .first { method ->
                    method.name == "M" &&
                        method.parameterTypes.contentEquals(arrayOf(sendingLog.javaClass))
                }.invoke(sendingLogManager, sendingLog)
            sendingLogManagerClass
                .methods
                .first { method ->
                    method.name == "N" &&
                        method.parameterTypes.contentEquals(arrayOf(sendingLog.javaClass))
                }.invoke(sendingLogManager, sendingLog)
            IrisLogger.info("[KakaoNativeImageReplySender] registered sending log with ChatSendingLogManager")
        }.onFailure {
            IrisLogger.error("[KakaoNativeImageReplySender] ChatSendingLogManager registration failed: ${it.message}", it)
            throw it
        }
        runCatching {
            val request =
                requestClass.declaredConstructors
                    .firstOrNull { constructor ->
                        constructor.parameterCount == 5 &&
                            constructor.parameterTypes[0].isAssignableFrom(chatRoom.javaClass) &&
                            constructor.parameterTypes[1].isAssignableFrom(sendingLog.javaClass) &&
                            constructor.parameterTypes[2] == writeTypeClass &&
                            constructor.parameterTypes[3] == Boolean::class.javaPrimitiveType &&
                            constructor.parameterTypes[4] == listenerClass
                    }?.apply { isAccessible = true }
                    ?.newInstance(chatRoom, sendingLog, writeTypeNone, false, null)
                    ?: error("ChatSendingLogRequest constructor not found")
            IrisLogger.info("[KakaoNativeImageReplySender] forcing request pipeline with direct ChatSendingLogRequest instance")
            request.javaClass.getMethod("W").invoke(request)
            IrisLogger.info("[KakaoNativeImageReplySender] ChatSendingLogRequest.W completed")
            request.javaClass.getMethod("Y").invoke(request)
            IrisLogger.info("[KakaoNativeImageReplySender] ChatSendingLogRequest.Y completed")
            request.javaClass.getMethod("a0").invoke(request)
            IrisLogger.info("[KakaoNativeImageReplySender] ChatSendingLogRequest.a0 completed")
        }.onFailure {
            IrisLogger.error("[KakaoNativeImageReplySender] forced request pipeline failed: ${it.message}", it)
            throw it
        }
    }

    private fun resolveScope(
        threadId: Long?,
        threadScope: Int?,
    ): Int =
        when {
            threadId == null -> 1
            threadScope == 3 -> 3
            else -> 2
        }

    @Suppress("UNCHECKED_CAST")
    private fun enumConstant(
        enumClass: Class<*>,
        name: String,
    ): Any =
        enumClass.enumConstants?.firstOrNull { (it as Enum<*>).name == name }
            ?: error("enum constant $name not found in ${enumClass.name}")

    private fun loadClass(
        loader: ClassLoader,
        className: String,
    ): Class<*> = Class.forName(className, true, loader)

    private fun loadChatMessageTypeClass(loader: ClassLoader): Class<*> =
        runCatching { loadClass(loader, "Op.EnumC16810c") }.getOrElse {
            loadClass(loader, "Op.c")
        }

    private fun resolveLoadedApk(
        activityThread: Any,
        systemContext: Context,
    ): Any {
        val applicationInfo = systemContext.packageManager.getApplicationInfo(KAKAO_PACKAGE, 0)
        val compatibilityInfoClass = Class.forName("android.content.res.CompatibilityInfo")
        val defaultCompatibilityInfo = compatibilityInfoClass.getDeclaredField("DEFAULT_COMPATIBILITY_INFO").get(null)
        return activityThread.javaClass
            .getDeclaredMethod(
                "getPackageInfoNoCheck",
                android.content.pm.ApplicationInfo::class.java,
                compatibilityInfoClass,
            ).apply { isAccessible = true }
            .invoke(activityThread, applicationInfo, defaultCompatibilityInfo)
            ?: error("unable to resolve LoadedApk for $KAKAO_PACKAGE")
    }

    @SuppressLint("PrivateApi")
    private fun resolveActivityThread(): Any {
        val activityThreadClass = Class.forName("android.app.ActivityThread")
        val current = activityThreadClass.getDeclaredMethod("currentActivityThread").invoke(null)
        return current
            ?: activityThreadClass.getDeclaredMethod("systemMain").invoke(null)
            ?: error("unable to resolve ActivityThread")
    }

    private fun resolveSystemContext(activityThread: Any): Context {
        val activityThreadClass = Class.forName("android.app.ActivityThread")
        val getSystemContext = activityThreadClass.getDeclaredMethod("getSystemContext")
        return getSystemContext.invoke(activityThread) as? Context
            ?: error("unable to resolve system context")
    }

    private fun <T> withContextClassLoader(
        loader: ClassLoader,
        block: () -> T,
    ): T {
        val thread = Thread.currentThread()
        val previous = thread.contextClassLoader
        thread.contextClassLoader = loader
        return try {
            block()
        } finally {
            thread.contextClassLoader = previous
        }
    }

    private fun <T> runOnMainLooper(block: () -> T): T {
        val mainLooper = ensureProcessMainLooper()
        if (Looper.myLooper() == mainLooper) {
            return block()
        }
        return runOnLooper(mainLooper, block)
    }

    private fun ensureProcessMainLooper(): Looper {
        Looper.getMainLooper()?.let { return it }
        preparedMainLooper?.let { return it }
        synchronized(mainLooperLock) {
            Looper.getMainLooper()?.let { return it }
            preparedMainLooper?.let { return it }

            val looperRef = AtomicReference<Looper?>()
            val latch = CountDownLatch(1)
            val thread =
                Thread {
                    Looper.prepareMainLooper()
                    looperRef.set(Looper.myLooper())
                    latch.countDown()
                    Looper.loop()
                }.apply {
                    name = "iris-kakao-main-looper"
                    isDaemon = true
                }
            thread.start()
            check(latch.await(15, TimeUnit.SECONDS)) { "timed out preparing process main looper" }
            val looper = looperRef.get() ?: error("process main looper preparation returned null")
            preparedMainLooper = looper
            IrisLogger.info("[KakaoNativeImageReplySender] prepared synthetic process main looper on ${thread.name}")
            return looper
        }
    }

    private fun <T> runOnDedicatedLooperThread(block: () -> T): T {
        val handlerThread = HandlerThread("iris-kakao-native-bootstrap")
        handlerThread.start()
        return try {
            runOnLooper(handlerThread.looper, block)
        } finally {
            handlerThread.quitSafely()
        }
    }

    private fun <T> runOnLooper(
        looper: Looper,
        block: () -> T,
    ): T {
        val result = AtomicReference<Any?>()
        val error = AtomicReference<Throwable?>()
        val latch = CountDownLatch(1)
        Handler(looper).post {
            try {
                result.set(block())
            } catch (throwable: Throwable) {
                error.set(throwable)
            } finally {
                latch.countDown()
            }
        }

        check(latch.await(15, TimeUnit.SECONDS)) { "timed out waiting for main looper bootstrap" }
        error.get()?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result.get() as T
    }

    private data class BootstrapState(
        val appContext: Context,
        val loader: ClassLoader,
        val masterDatabase: Any,
    )
}
