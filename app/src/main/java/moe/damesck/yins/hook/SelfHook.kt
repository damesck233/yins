package moe.damesck.yins.hook

import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/** Makes ModuleStatus.isActive() return true inside the manager app when the module is loaded. */
object SelfHook {
    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        XposedHelpers.findAndHookMethod(
            "moe.damesck.yins.ModuleStatus",
            lpparam.classLoader,
            "isActive",
            XC_MethodReplacement.returnConstant(true),
        )
    }
}
