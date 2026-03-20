package party.qwer.iris

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.IBinder

@SuppressLint("PrivateApi")
class AndroidHiddenApi {
    companion object {
        val startService: (Intent) -> Unit by lazy {
            getStartServiceMethod()
        }
        val startActivity: (Intent) -> Unit by lazy {
            getStartActivityMethod()
        }
        val broadcastIntent: (Intent) -> Unit by lazy {
            getBroadcastIntentMethod()
        }

        private val callingPackageName: String by lazy {
            System.getenv("IRIS_RUNNER") ?: "com.android.shell"
        }

        private val iActivityManagerClass: Class<*> by lazy {
            Class.forName("android.app.IActivityManager")
        }

        private val iApplicationThreadClass: Class<*> by lazy {
            Class.forName("android.app.IApplicationThread")
        }

        private val activityManager by lazy {
            val activityManagerStub = Class.forName("android.app.IActivityManager\$Stub")
            val binder = getService("activity")
            activityManagerStub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
        }

        private fun buildMethodResolutionError(
            methodName: String,
            clazz: Class<*>,
        ): Nothing {
            val methods =
                clazz.methods
                    .map {
                        it.toString().trim()
                    }.filter {
                        it.contains(methodName)
                    }.joinToString("\n")

            val errorMsg =
                """
                failed to get $methodName Method. Please report
                SDK: ${Build.VERSION.SDK_INT}
                METHODS: $methods
                """.trimIndent()

            IrisLogger.error(errorMsg)
            throw IllegalStateException(errorMsg)
        }

        private fun getStartServiceMethod(): (Intent) -> Unit {
            val iActivityManager = iActivityManagerClass
            val iApplicationThread = iApplicationThreadClass

            try {
                // IApplicationThread caller, Intent service, String resolvedType,
                // boolean requireForeground, String callingPackage, String callingFeatureId, int userId
                val method =
                    iActivityManager.getMethod(
                        "startService",
                        iApplicationThread,
                        Intent::class.java,
                        String::class.java,
                        java.lang.Boolean.TYPE,
                        String::class.java,
                        String::class.java,
                        java.lang.Integer.TYPE,
                    )

                return { intent ->
                    method.invoke(
                        activityManager,
                        null,
                        intent,
                        null,
                        false,
                        callingPackageName,
                        null,
                        -3,
                    )
                }
            } catch (_: Exception) {
            }

            try {
                // IApplicationThread caller, Intent service, String resolvedType,
                // boolean requireForeground, in String callingPackage, int userId);
                val method =
                    iActivityManager.getMethod(
                        "startService",
                        iApplicationThread,
                        Intent::class.java,
                        String::class.java,
                        java.lang.Boolean.TYPE,
                        String::class.java,
                        java.lang.Integer.TYPE,
                    )

                return { intent ->
                    method.invoke(
                        activityManager,
                        null,
                        intent,
                        null,
                        false,
                        callingPackageName,
                        -3,
                    )
                }
            } catch (_: Exception) {
            }

            buildMethodResolutionError("startService", iActivityManager)
        }

        private fun getStartActivityMethod(): (Intent) -> Unit {
            val iActivityManager = iActivityManagerClass
            val iApplicationThread = iApplicationThreadClass

            try {
                // IApplicationThread caller, String callingPackage, String callingFeatureId,
                // Intent intent, String resolvedType, IBinder resultTo, String resultWho,
                // int requestCode, int flags, ProfilerInfo profilerInfo, Bundle options, int userId
                val profilerInfo = Class.forName("android.app.ProfilerInfo")
                val method =
                    iActivityManager.getMethod(
                        "startActivity",
                        iApplicationThread,
                        String::class.java,
                        String::class.java,
                        Intent::class.java,
                        String::class.java,
                        IBinder::class.java,
                        String::class.java,
                        Integer.TYPE,
                        Integer.TYPE,
                        profilerInfo,
                        Bundle::class.java,
                        Integer.TYPE,
                    )

                return { intent ->
                    method.invoke(
                        activityManager,
                        null,
                        callingPackageName,
                        null,
                        intent,
                        intent.type,
                        null,
                        null,
                        0,
                        0,
                        null,
                        null,
                        -3,
                    )
                }
            } catch (_: Exception) {
            }

            try {
                // IApplicationThread, java.lang.String, android.content.Intent,
                // java.lang.String, android.os.IBinder, java.lang.String, int, int, android.app.ProfilerInfo, android.os.Bundle, int
                val profilerInfo = Class.forName("android.app.ProfilerInfo")
                val method =
                    iActivityManager.getMethod(
                        "startActivityAsUser",
                        iApplicationThread,
                        String::class.java,
                        Intent::class.java,
                        String::class.java,
                        IBinder::class.java,
                        String::class.java,
                        Integer.TYPE,
                        Integer.TYPE,
                        profilerInfo,
                        Bundle::class.java,
                        Integer.TYPE,
                    )

                return { intent ->
                    method.invoke(
                        activityManager,
                        null,
                        callingPackageName,
                        intent,
                        intent.type,
                        null,
                        null,
                        0,
                        0,
                        null,
                        null,
                        -3,
                    )
                }
            } catch (_: Exception) {
            }

            buildMethodResolutionError("startActivity", iActivityManager)
        }

        private fun getBroadcastIntentMethod(): (Intent) -> Unit {
            val iActivityManager = iActivityManagerClass
            val iApplicationThread = iApplicationThreadClass

            try {
                // IApplicationThread caller, Intent intent, String resolvedType,
                // IIntentReceiver resultTo, int resultCode, String resultData,
                // Bundle map, String[] requiredPermissions, int appOp, Bundle options,
                // boolean serialized, boolean sticky, int userId
                val iIntentReceiver = Class.forName("android.content.IIntentReceiver")
                val method =
                    iActivityManager.getMethod(
                        "broadcastIntent",
                        iApplicationThread,
                        Intent::class.java,
                        String::class.java,
                        iIntentReceiver,
                        Integer.TYPE,
                        String::class.java,
                        Bundle::class.java,
                        Array<String>::class.java,
                        Integer.TYPE,
                        Bundle::class.java,
                        Boolean::class.java,
                        Boolean::class.java,
                        Int::class.java,
                    )

                return { intent ->
                    method.invoke(
                        activityManager,
                        null,
                        intent,
                        null,
                        null,
                        0,
                        null,
                        null,
                        null,
                        -1,
                        null,
                        false,
                        false,
                        -3,
                    )
                }
            } catch (_: Exception) {
            }

            buildMethodResolutionError("broadcastIntent", iActivityManager)
        }

        private fun getService(name: String): IBinder {
            val method = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)

            return method.invoke(null, name) as IBinder
        }
    }
}
