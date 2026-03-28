@file:Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")

package party.qwer.iris

import android.os.IBinder
import android.service.notification.StatusBarNotification
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

interface ActiveNotificationFeed {
    fun snapshot(): List<StatusBarNotification>
}

class HiddenNotificationFeed(
    private val targetPackage: String = KAKAO_TALK_PACKAGE,
    private val callerPackage: String = System.getenv("IRIS_RUNNER") ?: "com.android.shell",
) : ActiveNotificationFeed {
    private val notificationManager: Any by lazy(LazyThreadSafetyMode.PUBLICATION) {
        connectToNotificationManager()
    }

    private val userId: Int by lazy(LazyThreadSafetyMode.PUBLICATION) {
        resolveCurrentUserId()
    }

    private val notificationQueries: List<NotificationQuery> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        listOf(
            NotificationQuery("getAppActiveNotifications", arrayOf<Any>(targetPackage, userId)),
            NotificationQuery("getActiveNotifications", arrayOf<Any>(callerPackage)),
            NotificationQuery("getActiveNotifications", arrayOf<Any>(targetPackage)),
        )
    }

    private val notificationManagerMethods by lazy(LazyThreadSafetyMode.PUBLICATION) {
        notificationManager.javaClass.methods
    }
    private val resolvedMethods = ConcurrentHashMap<NotificationQuery, Method?>()
    private val listMethodCache = ConcurrentHashMap<Class<*>, Method?>()

    override fun snapshot(): List<StatusBarNotification> {
        val manager = runCatching { notificationManager }.getOrNull() ?: return emptyList()

        for (attempt in notificationQueries) {
            val result = runCatching { invokeMatching(manager, attempt) }.getOrNull() ?: continue
            val notifications = unwrap(result).filter { it.packageName == targetPackage }
            if (notifications.isNotEmpty()) {
                return notifications
            }
        }

        return emptyList()
    }

    private fun connectToNotificationManager(): Any {
        val binder =
            Class
                .forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java)
                .invoke(null, "notification") as IBinder

        val stub = Class.forName("android.app.INotificationManager\$Stub")
        return stub
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder)
            ?: error("Failed to connect to notification manager")
    }

    private fun resolveCurrentUserId(): Int =
        try {
            val userHandle = Class.forName("android.os.UserHandle")
            userHandle.getMethod("myUserId").invoke(null) as Int
        } catch (_: Exception) {
            0
        }

    private fun invokeMatching(
        receiver: Any,
        query: NotificationQuery,
    ): Any? {
        val method =
            resolvedMethods.getOrPut(query) {
                notificationManagerMethods.firstOrNull { candidate ->
                    candidate.name == query.methodName &&
                        candidate.parameterTypes.size == query.args.size &&
                        candidate.parameterTypes.indices.all { index ->
                            isCompatible(candidate.parameterTypes[index], query.args[index])
                        }
                }
            } ?: return null

        return method.invoke(receiver, *query.args)
    }

    private fun isCompatible(
        expected: Class<*>,
        value: Any,
    ): Boolean =
        when (expected) {
            java.lang.Integer.TYPE, Integer::class.java -> value is Int
            java.lang.Long.TYPE, java.lang.Long::class.java -> value is Long
            java.lang.Boolean.TYPE, java.lang.Boolean::class.java -> value is Boolean
            else -> expected.isAssignableFrom(value.javaClass)
        }

    private fun unwrap(result: Any?): List<StatusBarNotification> =
        when (result) {
            null -> emptyList()
            is Array<*> -> result.filterIsInstance<StatusBarNotification>()
            is Iterable<*> -> result.filterIsInstance<StatusBarNotification>()
            else -> {
                val getList =
                    listMethodCache.getOrPut(result.javaClass) {
                        result.javaClass.methods.firstOrNull {
                            it.name == "getList" && it.parameterCount == 0
                        }
                    } ?: return emptyList()

                unwrap(runCatching { getList.invoke(result) }.getOrNull())
            }
        }

    private data class NotificationQuery(
        val methodName: String,
        val args: Array<Any>,
    )
}
