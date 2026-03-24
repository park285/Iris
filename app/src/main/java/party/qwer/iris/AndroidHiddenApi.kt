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
            getStartActivityMethod(callingPackageName)
        }
        val broadcastIntent: (Intent) -> Unit by lazy {
            getBroadcastIntentMethod()
        }

        // thread graft 안전: callingPkg가 attachment JSON에 기록되므로
        // 반드시 com.kakao.talk으로 설정하여 정상 발송과 동일하게 만든다
        private val callingPackageName: String by lazy {
            System.getenv("IRIS_RUNNER") ?: "com.kakao.talk"
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

        private fun <T> resolveMethod(
            methodName: String,
            targetClass: Class<*>,
            vararg candidates: () -> T,
        ): T {
            for (candidate in candidates) {
                try {
                    return candidate()
                } catch (_: Exception) {
                    // 다음 후보 시도
                }
            }
            buildMethodResolutionError(methodName, targetClass)
        }

        private fun getStartServiceMethod(): (Intent) -> Unit {
            val iActivityManager = iActivityManagerClass
            val iApplicationThread = iApplicationThreadClass

            return resolveMethod(
                "startService",
                iActivityManager,
                {
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
                    (
                        { intent: Intent ->
                            method.invoke(activityManager, null, intent, null, false, callingPackageName, null, -3)
                        }
                    )
                },
                {
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
                    (
                        { intent: Intent ->
                            method.invoke(activityManager, null, intent, null, false, callingPackageName, -3)
                        }
                    )
                },
            )
        }

        fun startActivityAs(
            callingPackageName: String,
            intent: Intent,
        ) {
            getStartActivityMethod(callingPackageName)(intent)
        }

        private fun getStartActivityMethod(callerPackageName: String): (Intent) -> Unit {
            val iActivityManager = iActivityManagerClass
            val iApplicationThread = iApplicationThreadClass

            return resolveMethod(
                "startActivity",
                iActivityManager,
                {
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
                    (
                        { intent: Intent ->
                            method.invoke(
                                activityManager,
                                null,
                                callerPackageName,
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
                    )
                },
                {
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
                    (
                        { intent: Intent ->
                            method.invoke(
                                activityManager,
                                null,
                                callerPackageName,
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
                    )
                },
            )
        }

        private fun getBroadcastIntentMethod(): (Intent) -> Unit {
            val iActivityManager = iActivityManagerClass
            val iApplicationThread = iApplicationThreadClass

            return resolveMethod(
                "broadcastIntent",
                iActivityManager,
                {
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
                            java.lang.Boolean.TYPE,
                            java.lang.Boolean.TYPE,
                            Integer.TYPE,
                        )
                    (
                        { intent: Intent ->
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
                    )
                },
            )
        }

        private fun getService(name: String): IBinder {
            val method = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)

            return method.invoke(null, name) as IBinder
        }
    }
}
