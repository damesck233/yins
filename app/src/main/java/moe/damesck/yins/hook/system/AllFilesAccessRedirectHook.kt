package moe.damesck.yins.hook.system

import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.UserHandle
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import moe.damesck.yins.YLog
import moe.damesck.yins.data.PolicyContract
import moe.damesck.yins.hook.DecisionIntents

/**
 * Inside system_server: the "All files access" flow is a Settings page, not a permission dialog.
 * Rewrite the intent that opens it so it lands on the module's DecisionActivity instead.
 */
object AllFilesAccessRedirectHook {
    private const val ATMS = "com.android.server.wm.ActivityTaskManagerService"
    private const val ACTION_APP = "android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION"
    private const val ACTION_ALL = "android.settings.MANAGE_ALL_FILES_ACCESS_PERMISSION"

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        val atms = XposedHelpers.findClass(ATMS, lpparam.classLoader)
        XposedBridge.hookAllMethods(atms, "startActivityAsUser", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    maybeRedirect(param)
                } catch (t: Throwable) {
                    YLog.e("redirect failed, passing through", t)
                }
            }
        })
        YLog.i("system_server hooked")
    }

    private fun maybeRedirect(param: XC_MethodHook.MethodHookParam) {
        val intent = param.args.firstOrNull { it is Intent } as? Intent ?: return
        val action = intent.action ?: return
        if (action != ACTION_APP && action != ACTION_ALL) return
        if (intent.component != null) return

        val callingPackage = param.args.getOrNull(1) as? String
        val target = intent.data?.takeIf { it.scheme == "package" }?.schemeSpecificPart ?: callingPackage ?: return
        if (target == PolicyContract.MANAGER_PACKAGE) return
        // Only redirect when the app is asking for itself; Settings/other system UIs opening the
        // page for a third package are left alone.
        if (callingPackage != null && callingPackage != target) return

        val context = XposedHelpers.getObjectField(param.thisObject, "mContext") as? Context
        if (context != null && !isThirdPartyApp(context, target)) return

        YLog.i("redirecting all-files-access request from $target")
        val permissions = arrayOf(PolicyContract.PERM_MANAGE_EXTERNAL_STORAGE)

        // Launch the dialog ourselves as the system rather than rewriting the app's own start:
        // a third-party app starting yins directly trips ColorOS's "X wants to open yins"
        // cross-app confirmation, which the system uid is exempt from.
        if (context != null && startAsSystem(context, target, permissions, userIdOf(param))) {
            param.result = START_SUCCESS
            return
        }
        DecisionIntents.redirect(intent, target, permissions, DecisionIntents.SOURCE_ALL_FILES)
    }

    private fun startAsSystem(context: Context, target: String, permissions: Array<String>, userId: Int): Boolean {
        // MULTIPLE_TASK: always a fresh task, otherwise the dialog would join (and surface) the
        // manager's own task instead of floating over the requesting app.
        val launch = DecisionIntents.build(target, permissions, DecisionIntents.SOURCE_ALL_FILES)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
            )
        val user = UserHandle.getUserHandleForUid(userId * PER_USER_RANGE)
        val token = Binder.clearCallingIdentity()
        return try {
            XposedHelpers.callMethod(context, "startActivityAsUser", launch, user)
            true
        } catch (t: Throwable) {
            YLog.w("system launch of DecisionActivity failed, rewriting intent instead", t)
            false
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    /** `userId` is the last int parameter of every startActivityAsUser overload. */
    private fun userIdOf(param: XC_MethodHook.MethodHookParam): Int =
        param.args.lastOrNull { it is Int } as? Int ?: 0

    private const val START_SUCCESS = 0 // ActivityManager.START_SUCCESS
    private const val PER_USER_RANGE = 100_000

    private fun isThirdPartyApp(context: Context, packageName: String): Boolean = try {
        val info = context.packageManager.getApplicationInfo(packageName, 0)
        (info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0
    } catch (t: Throwable) {
        false
    }
}
