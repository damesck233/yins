package moe.damesck.yins.hook

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import moe.damesck.yins.YLog
import moe.damesck.yins.data.PolicyContract
import moe.damesck.yins.hook.app.AppHooks
import moe.damesck.yins.hook.mediaprovider.MediaProviderHooks
import moe.damesck.yins.hook.permcontroller.GrantPermissionsHook
import moe.damesck.yins.hook.system.AllFilesAccessRedirectHook
import moe.damesck.yins.hook.system.AppOpsSpoofHook
import moe.damesck.yins.hook.system.LiveUpdatePromotionHook

class HookEntry : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        YLog.xposedLogger = { XposedBridge.log("Yins: $it") }
        try {
            when (lpparam.packageName) {
                PolicyContract.MANAGER_PACKAGE -> SelfHook.install(lpparam)
                in MEDIA_PROVIDER_PACKAGES -> MediaProviderHooks.install(lpparam)
                in PERMISSION_CONTROLLER_PACKAGES -> GrantPermissionsHook.install(lpparam)
                "android" -> if (lpparam.processName == "android") {
                    AllFilesAccessRedirectHook.install(lpparam)
                    AppOpsSpoofHook.install(lpparam)
                    LiveUpdatePromotionHook.install(lpparam)
                }
                in SYSTEM_PACKAGES -> Unit
                // Any other scoped package is a managed target app: it self-gates on having a policy.
                else -> AppHooks.install(lpparam)
            }
        } catch (t: Throwable) {
            YLog.e("failed to install hooks in ${lpparam.packageName}", t)
        }
    }

    companion object {
        val MEDIA_PROVIDER_PACKAGES = setOf(
            "com.google.android.providers.media.module",
            "com.android.providers.media.module",
        )
        val PERMISSION_CONTROLLER_PACKAGES = setOf(
            "com.google.android.permissioncontroller",
            "com.android.permissioncontroller",
        )

        /** Scoped system packages that must never be treated as managed target apps. */
        val SYSTEM_PACKAGES = setOf("android")
    }
}
