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
        if (!names.all { PolicyContract.isStoragePermission(it) }) {
            if (names.any { PolicyContract.isStoragePermission(it) }) {
                YLog.i("mixed permission request from ${activity.callingPackage}, not intercepting: ${names.joinToString()}")
            }
            return
        }
        val target = targetPackage(activity)
        if (target == null) {
            YLog.w("cannot determine requesting package (callingPackage=null, action=${activity.intent.action}); leaving system dialog")
            return
        }
        if (target == PolicyContract.MANAGER_PACKAGE) return
        YLog.i("intercepting storage request from $target: ${names.joinToString()}")
        try {
            activity.startActivityForResult(
                DecisionIntents.build(target, names, DecisionIntents.SOURCE_RUNTIME),
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
        val mode = Mode.fromName(data?.getStringExtra(DecisionIntents.EXTRA_RESULT_MODE))
        if (resultCode != Activity.RESULT_OK || mode == null) {
            YLog.i("decision cancelled, falling back to system dialog")
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
