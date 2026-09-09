package moe.damesck.yins.hook.app

import android.app.AndroidAppHelper
import android.os.Environment
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import moe.damesck.yins.YLog
import moe.damesck.yins.data.Mode
import moe.damesck.yins.data.PolicySnapshot
import kotlin.concurrent.thread

/**
 * Runs inside a managed target app (BLANK / PARTIAL). We do NOT grant MANAGE_EXTERNAL_STORAGE for
 * real, because a real all-files grant bypasses MediaProvider filtering. Instead the app is made to
 * *believe* it holds all-files access:
 *   - Environment.isExternalStorageManager() -> true
 *   - AppOpsManager checks of OPSTR_MANAGE_EXTERNAL_STORAGE for our own uid -> allowed
 *
 * Runtime media permissions are really granted, so checkSelfPermission already returns granted and
 * needs no spoofing. All raw file/MediaStore access still goes through MediaProvider, which filters.
 */
object AppHooks {
    private const val OPSTR_MANAGE_EXTERNAL_STORAGE = "android:manage_external_storage"
    private const val MODE_ALLOWED = 0

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        // Resolve the policy for this package off the main thread; install spoofs only if needed.
        thread(name = "yins-app-policy") {
            val mode = resolveMode(lpparam.packageName) ?: return@thread
            if (mode != Mode.BLANK && mode != Mode.PARTIAL) return@thread
            try {
                spoofAllFilesAccess(lpparam.classLoader)
                YLog.i("app spoof installed for ${lpparam.packageName} ($mode)")
            } catch (t: Throwable) {
                YLog.e("app spoof failed for ${lpparam.packageName}", t)
            }
        }
    }

    private fun resolveMode(packageName: String): Mode? {
        val ctx = try {
            AndroidAppHelper.currentApplication()
        } catch (t: Throwable) {
            null
        } ?: return null
        // Retry briefly: the provider may not be reachable the instant the app starts.
        repeat(5) {
            val snap = PolicySnapshot.load(ctx)
            if (snap != null) return snap.get(packageName, android.os.Process.myUid() / 100_000)
            Thread.sleep(500)
        }
        return null
    }

    private fun spoofAllFilesAccess(cl: ClassLoader) {
        XposedHelpers.findAndHookMethod(
            Environment::class.java, "isExternalStorageManager", XC_MethodReplacement.returnConstant(true),
        )
        // Some apps call the (UserHandle, String) overload.
        runCatching {
            XposedBridge.hookAllMethods(Environment::class.java, "isExternalStorageManager", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    param.result = true
                }
            })
        }

        // AppOpsManager: report MANAGE_EXTERNAL_STORAGE allowed for our own uid only.
        val appOps = XposedHelpers.findClassIfExists("android.app.AppOpsManager", cl) ?: return
        val myUid = android.os.Process.myUid()
        val opHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val op = param.args.getOrNull(0)
                val opStr = op as? String
                val isManageOp = opStr == OPSTR_MANAGE_EXTERNAL_STORAGE ||
                    (op is Int && runCatching { XposedHelpers.callStaticMethod(appOps, "opToPublicName", op) }.getOrNull() == OPSTR_MANAGE_EXTERNAL_STORAGE)
                if (!isManageOp) return
                // Only spoof self-checks; the uid arg (if present) must be ours.
                val uidArg = param.args.firstOrNull { it is Int && it >= 10000 } as? Int
                if (uidArg != null && uidArg != myUid) return
                param.result = MODE_ALLOWED
            }
        }
        for (name in listOf("checkOpNoThrow", "unsafeCheckOpNoThrow", "unsafeCheckOpRawNoThrow", "noteOpNoThrow", "noteProxyOpNoThrow")) {
            runCatching { XposedBridge.hookAllMethods(appOps, name, opHook) }
        }
    }
}
