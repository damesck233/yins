package moe.damesck.yins.hook.permcontroller

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import moe.damesck.yins.YLog
import moe.damesck.yins.data.Mode
import moe.damesck.yins.data.PolicyContract
import moe.damesck.yins.hook.DecisionIntents

/**
 * Inside PermissionController: when an app's runtime permission request consists solely of
 * storage/media permissions, show the module's DecisionActivity on top of the system dialog and
 * answer the request with its outcome. If the user dismisses our dialog, the system dialog
 * underneath takes over unchanged.
 */
object GrantPermissionsHook {
    private const val GRANT_ACTIVITY = "com.android.permissioncontroller.permission.ui.GrantPermissionsActivity"
    private const val REQUEST_CODE = 0x7159

    // Instance fields / loop guard for the mixed-request flow.
    private const val FIELD_MIXED = "yins.mixed"
    private const val FIELD_SIG = "yins.sig"
    private const val HANDLED_WINDOW_MS = 8_000L
    private val recentlyHandled = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun signature(target: String, names: Array<String>): String =
        target + "|" + names.sorted().joinToString(",")

    // Hidden PackageManager extras used by Activity.requestPermissions / dispatchRequestPermissionsResult.
    private const val EXTRA_NAMES = "android.content.pm.extra.REQUEST_PERMISSIONS_NAMES"
    private const val EXTRA_RESULTS = "android.content.pm.extra.REQUEST_PERMISSIONS_RESULTS"
    private const val EXTRA_DEVICE_ID = "android.content.pm.extra.REQUEST_PERMISSIONS_DEVICE_ID"

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        val grantClass = XposedHelpers.findClass(GRANT_ACTIVITY, lpparam.classLoader)

        XposedHelpers.findAndHookMethod(grantClass, "onCreate", Bundle::class.java, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    onCreated(param.thisObject as Activity)
                } catch (t: Throwable) {
                    YLog.e("GrantPermissionsActivity.onCreate hook failed", t)
                }
            }
        })

        // Activity.dispatchActivityResult(String who, int requestCode, int resultCode, Intent data, String reason)
        XposedBridge.hookAllMethods(Activity::class.java, "dispatchActivityResult", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val activity = param.thisObject as Activity
                if (!grantClass.isInstance(activity)) return
                val requestCode = param.args.getOrNull(1) as? Int ?: return
                if (requestCode != REQUEST_CODE) return
                param.result = null // swallow: the original activity never learns about our request
                try {
                    onDecision(activity, param.args.getOrNull(2) as? Int ?: Activity.RESULT_CANCELED, param.args.getOrNull(3) as? Intent)
                } catch (t: Throwable) {
                    YLog.e("decision handling failed", t)
                }
            }
        })
        YLog.i("PermissionController hooked")
    }

    private fun onCreated(activity: Activity) {
        if (activity.isFinishing) return
        val names = activity.intent.getStringArrayExtra(EXTRA_NAMES) ?: return
        if (names.isEmpty()) return
        val storagePerms = names.filter { PolicyContract.isStoragePermission(it) }
        if (storagePerms.isEmpty()) return
        val target = targetPackage(activity)
        if (target == null) {
            YLog.w("cannot determine requesting package (callingPackage=null, action=${activity.intent.action}); leaving system dialog")
            return
        }
        if (target == PolicyContract.MANAGER_PACKAGE) return

        // Mixed request (storage + something else, e.g. camera): we handle only the storage part in
        // yins, then let the system dialog finish the rest. The signature guard breaks the loop when
        // we recreate() the activity after applying our decision.
        val mixed = storagePerms.size < names.size
        val sig = signature(target, names)
        if (mixed && System.currentTimeMillis() - (recentlyHandled[sig] ?: 0L) < HANDLED_WINDOW_MS) {
            YLog.i("mixed request already offered to yins; system handles the rest for $target")
            return
        }
        XposedHelpers.setAdditionalInstanceField(activity, FIELD_MIXED, mixed)
        XposedHelpers.setAdditionalInstanceField(activity, FIELD_SIG, sig)
        YLog.i("intercepting ${if (mixed) "mixed" else "storage"} request from $target: ${storagePerms.joinToString()}")
        try {
            activity.startActivityForResult(
                DecisionIntents.build(target, storagePerms.toTypedArray(), DecisionIntents.SOURCE_RUNTIME),
                REQUEST_CODE,
            )
        } catch (t: Throwable) {
            YLog.e("could not launch DecisionActivity; leaving system dialog", t)
        }
    }

    /**
     * The requesting app: normally the activity's calling package; for requests made on behalf of
     * another app (REQUEST_PERMISSIONS_FOR_OTHER) it is carried in EXTRA_PACKAGE_NAME.
     */
    private fun targetPackage(activity: Activity): String? {
        activity.callingPackage?.let { return it }
        val fromExtra = activity.intent.getStringExtra(Intent.EXTRA_PACKAGE_NAME)
        if (fromExtra != null) return fromExtra
        // Last resort: the activity that launched us, if the framework still knows it.
        return try {
            activity.callingActivity?.packageName
        } catch (t: Throwable) {
            null
        }
    }

    private fun onDecision(activity: Activity, resultCode: Int, data: Intent?) {
        val mixed = XposedHelpers.getAdditionalInstanceField(activity, FIELD_MIXED) as? Boolean ?: false
        if (mixed) {
            // yins has applied its decision to the storage perms (or the user cancelled). Those are
            // now set at the OS level, so re-run the system flow: granted ones are skipped and it
            // only prompts for what is left (camera, contacts, ...). The signature guard stops us
            // from intercepting the recreated instance again.
            (XposedHelpers.getAdditionalInstanceField(activity, FIELD_SIG) as? String)?.let {
                recentlyHandled[it] = System.currentTimeMillis()
            }
            YLog.i("mixed request: storage part handled by yins, letting system finish the rest")
            try {
                activity.recreate()
            } catch (t: Throwable) {
                YLog.e("recreate after mixed decision failed", t)
            }
            return
        }
        val mode = Mode.fromName(data?.getStringExtra(DecisionIntents.EXTRA_RESULT_MODE))
        if (resultCode != Activity.RESULT_OK || mode == null) {
            // The user dismissed our dialog (cancel button or back). We *replace* the system dialog
            // rather than sit on top of it, so close the system activity too instead of revealing
            // it. Finishing without permission results makes the framework report the request as
            // cancelled — the same as swiping the system dialog away.
            YLog.i("decision dismissed; closing the system dialog")
            activity.setResult(Activity.RESULT_CANCELED)
            activity.finish()
            return
        }
        val names = activity.intent.getStringArrayExtra(EXTRA_NAMES) ?: emptyArray()
        val target = targetPackage(activity)
        val granted = mode != Mode.DENY
        val results = IntArray(names.size) {
            if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
        }
        val result = Intent()
            .putExtra(EXTRA_NAMES, names)
            .putExtra(EXTRA_RESULTS, results)
        if (activity.intent.hasExtra(EXTRA_DEVICE_ID)) {
            result.putExtra(EXTRA_DEVICE_ID, activity.intent.getIntExtra(EXTRA_DEVICE_ID, 0))
        }
        activity.setResult(Activity.RESULT_OK, result)
        activity.finish()
        YLog.i("answered $target with $mode")
    }
}
