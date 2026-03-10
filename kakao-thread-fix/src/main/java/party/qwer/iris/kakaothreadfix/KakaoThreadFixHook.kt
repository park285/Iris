package party.qwer.iris.kakaothreadfix

import android.content.Intent
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

class KakaoThreadFixHook : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        if (lpparam.packageName != KAKAO_PACKAGE) {
            return
        }

        hookIntentCompat(lpparam)
        hookCommunityThreadForwardCtor(lpparam)
    }

    private fun hookIntentCompat(lpparam: LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                "C2.c",
                lpparam.classLoader,
                "c",
                Intent::class.java,
                String::class.java,
                Class::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val key = param.args.getOrNull(1) as? String ?: return
                        if (key != EXTRA_COMMUNITY_THREAD_INFO) {
                            return
                        }

                        val intent = param.args.getOrNull(0) as? Intent ?: return
                        val clazz = param.args.getOrNull(2) as? Class<*> ?: return

                        try {
                            intent.setExtrasClassLoader(clazz.classLoader)
                        } catch (t: Throwable) {
                            XposedBridge.log("KakaoThreadFix: setExtrasClassLoader failed: $t")
                        }
                    }
                },
            )
            XposedBridge.log("KakaoThreadFix: hooked C2.c.c(Intent,String,Class)")
        } catch (t: Throwable) {
            XposedBridge.log("KakaoThreadFix: hookIntentCompat failed: $t")
        }
    }

    private fun hookCommunityThreadForwardCtor(lpparam: LoadPackageParam) {
        // Stacktrace name in current KakaoTalk: Ip.n.<init>(ConnectionCommunityThreadForward.kt:13)
        try {
            XposedHelpers.findAndHookConstructor(
                "Ip.n",
                lpparam.classLoader,
                Intent::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val intent = param.args.getOrNull(0) as? Intent ?: return

                        try {
                            intent.setExtrasClassLoader(lpparam.classLoader)
                        } catch (t: Throwable) {
                            XposedBridge.log("KakaoThreadFix: setExtrasClassLoader in Ip.n ctor failed: $t")
                        }
                    }
                },
            )
            XposedBridge.log("KakaoThreadFix: hooked Ip.n(Intent) ctor")
        } catch (t: Throwable) {
            XposedBridge.log("KakaoThreadFix: hookCommunityThreadForwardCtor failed: $t")
        }
    }

    private companion object {
        private const val KAKAO_PACKAGE = "com.kakao.talk"
        private const val EXTRA_COMMUNITY_THREAD_INFO = "EXTRA_COMMUNITY_THREAD_INFO"
    }
}
