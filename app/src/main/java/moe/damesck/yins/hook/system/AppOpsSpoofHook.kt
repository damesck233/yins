package moe.damesck.yins.hook.system

import android.app.AppOpsManager
import android.content.Context
import android.os.Binder
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import moe.damesck.yins.YLog
import moe.damesck.yins.data.Mode

/**
 * Inside system_server: make a BLANK / PARTIAL app believe it holds "all files access".
 *
 * The real MANAGE_EXTERNAL_STORAGE appop stays *ignored* (a real grant would bypass MediaProvider's
 * filtering), but when the app itself asks AppOpsService about its own op — which is what
 * `Environment.isExternalStorageManager()` boils down to — the answer is rewritten to allowed.
 * Every other caller (MediaProvider deciding what the app may see, shell, Settings) gets the truth,
 * so the protection is unaffected and the app no longer needs to be in the module's LSPosed scope.
 */
object AppOpsSpoofHook {
    private const val APP_OPS_SERVICE = "com.android.server.appop.AppOpsService"
    private const val OPSTR_MANAGE_EXTERNAL_STORAGE = "android:manage_external_storage"
    private const val DEFAULT_OP_CODE = 92 // AppOpsManager.OP_MANAGE_EXTERNAL_STORAGE
    private const val MODE_ALLOWED = AppOpsManager.MODE_ALLOWED
    private const val PER_USER_RANGE = 100_000

    private var opCode = DEFAULT_OP_CODE

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        val service = XposedHelpers.findClass(APP_OPS_SERVICE, lpparam.classLoader)
        opCode = runCatching {
            XposedHelpers.callStaticMethod(AppOpsManager::class.java, "strOpToOp", OPSTR_MANAGE_EXTERNAL_STORAGE) as Int
        }.getOrDefault(DEFAULT_OP_CODE)

        // Binder entry points come in several overloads per release (plain, Raw, ForDevice...);
        // hook every method of the two families rather than guessing signatures.
        val names = service.declaredMethods
            .map { it.name }
            .filter { it.startsWith("checkOperation") || it.startsWith("noteOperation") }
            .toSet()
        for (name in names) XposedBridge.hookAllMethods(service, name, SpoofHook)

        // Start pulling policies as soon as the service is up instead of on first use.
        runCatching {
            XposedBridge.hookAllMethods(service, "systemReady", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    (XposedHelpers.getObjectField(param.thisObject, "mContext") as? Context)?.let { SystemPolicyCache.start(it) }
                }
            })
        }
        YLog.i("appops spoof hooked ${names.size} methods (op $opCode)")
    }

    private object SpoofHook : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            try {
                spoof(param)
            } catch (t: Throwable) {
                YLog.e("appops spoof failed", t)
            }
        }
    }

    private fun spoof(param: XC_MethodHook.MethodHookParam) {
        val code = param.args.getOrNull(0) as? Int ?: return
        if (code != opCode) return
        val uid = param.args.getOrNull(1) as? Int ?: return
        val packageName = param.args.getOrNull(2) as? String ?: return
        // Only the app asking about itself.
        if (Binder.getCallingUid() != uid) return

        val result = param.result ?: return
        val currentMode = when (result) {
            is Int -> result
            else -> runCatching { XposedHelpers.callMethod(result, "getOpMode") as Int }.getOrNull() ?: return
        }
        if (currentMode == MODE_ALLOWED) return

        val context = XposedHelpers.getObjectField(param.thisObject, "mContext") as? Context ?: return
        SystemPolicyCache.start(context)
        val mode = SystemPolicyCache.get(packageName, uid / PER_USER_RANGE) ?: return
        if (mode != Mode.BLANK && mode != Mode.PARTIAL) return

        // system_server also consults these ops internally while servicing a call from the app
        // (e.g. picking the storage mount mode for a process it is about to spawn). Those must see
        // the real value, so only rewrite answers that go straight back over binder to the app.
        if (!isDirectBinderCall()) return

        param.result = when (result) {
            is Int -> MODE_ALLOWED
            // SyncNotedAppOp(opMode, opCode, attributionTag, packageName)
            else -> XposedHelpers.newInstance(
                result.javaClass,
                MODE_ALLOWED,
                code,
                runCatching { XposedHelpers.callMethod(result, "getAttributionTag") as? String }.getOrNull(),
                packageName,
            )
        }
    }

    private fun isDirectBinderCall(): Boolean {
        val stack = Thread.currentThread().stackTrace
        for (frame in stack) {
            if (frame.methodName == "onTransact" && frame.className.endsWith("IAppOpsService\$Stub")) return true
            // Anything from the storage / activity managers means an internal consult, not the app.
            if (frame.className.startsWith("com.android.server.StorageManagerService") ||
                frame.className.startsWith("com.android.server.am.") ||
                frame.className.startsWith("com.android.server.wm.")
            ) return false
        }
        return false
    }
}
