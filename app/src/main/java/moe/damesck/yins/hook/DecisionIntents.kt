package moe.damesck.yins.hook

import android.content.Intent
import moe.damesck.yins.data.PolicyContract

/** Intent contract of moe.damesck.yins.ui.DecisionActivity, shared with the hooks that launch it. */
object DecisionIntents {
    const val ACTIVITY = "moe.damesck.yins.ui.DecisionActivity"

    const val EXTRA_TARGET_PACKAGE = "yins.targetPackage"
    const val EXTRA_PERMISSIONS = "yins.permissions"
    const val EXTRA_SOURCE = "yins.source"
    const val EXTRA_RESULT_MODE = "yins.resultMode"

    const val SOURCE_RUNTIME = "runtime"
    const val SOURCE_ALL_FILES = "all_files"

    fun build(targetPackage: String, permissions: Array<String>, source: String): Intent =
        Intent()
            .setClassName(PolicyContract.MANAGER_PACKAGE, ACTIVITY)
            .putExtra(EXTRA_TARGET_PACKAGE, targetPackage)
            .putExtra(EXTRA_PERMISSIONS, permissions)
            .putExtra(EXTRA_SOURCE, source)

    /** Rewrites [intent] in place so it launches DecisionActivity instead of its original target. */
    fun redirect(intent: Intent, targetPackage: String, permissions: Array<String>, source: String) {
        intent.setClassName(PolicyContract.MANAGER_PACKAGE, ACTIVITY)
        intent.action = null
        intent.putExtra(EXTRA_TARGET_PACKAGE, targetPackage)
        intent.putExtra(EXTRA_PERMISSIONS, permissions)
        intent.putExtra(EXTRA_SOURCE, source)
    }
}
