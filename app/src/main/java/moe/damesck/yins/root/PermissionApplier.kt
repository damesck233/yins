package moe.damesck.yins.root

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.damesck.yins.YLog
import moe.damesck.yins.data.Mode
import moe.damesck.yins.data.PolicyContract

/**
 * Translates a policy decision into the root shell commands that put the OS into the matching state.
 *
 * FULL / BLANK / PARTIAL all perform a *real* grant: the app's own permission checks must report
 * "granted" without any spoofing. What the app can actually see is decided inside MediaProvider.
 */
class NoRootException : IllegalStateException("no root")

object PermissionApplier {
    fun commands(
        packageName: String,
        requestedPermissions: Collection<String>,
        mode: Mode,
        forceStop: Boolean,
    ): List<String> {
        require(packageName.matches(Regex("[A-Za-z0-9_.]+"))) { "bad package name: $packageName" }

        val runtime = requestedPermissions
            .filter { it in PolicyContract.RUNTIME_STORAGE_PERMISSIONS }
            .toMutableSet()
        val wantsAllFiles = PolicyContract.PERM_MANAGE_EXTERNAL_STORAGE in requestedPermissions

        val cmds = ArrayList<String>()
        when (mode) {
            Mode.DENY -> {
                runtime.forEach { cmds += "pm revoke $packageName $it" }
                if (wantsAllFiles) cmds += "appops set --uid $packageName MANAGE_EXTERNAL_STORAGE ignore"
            }
            Mode.FULL -> {
                runtime.forEach { cmds += "pm grant $packageName $it" }
                if (wantsAllFiles) cmds += "appops set --uid $packageName MANAGE_EXTERNAL_STORAGE allow"
            }
            Mode.BLANK, Mode.PARTIAL -> {
                // Runtime media permissions are granted for real: MediaProvider funnels those
                // through hasPermission, which we mask. MANAGE_EXTERNAL_STORAGE is NOT granted,
                // because a real all-files grant bypasses MediaProvider entirely; instead the
                // app-process hook spoofs Environment.isExternalStorageManager() so the app still
                // believes it has all-files access.
                if (mode == Mode.PARTIAL) runtime += PolicyContract.PERM_READ_MEDIA_VISUAL_USER_SELECTED
                runtime.forEach { cmds += "pm grant $packageName $it" }
                if (wantsAllFiles) cmds += "appops set --uid $packageName MANAGE_EXTERNAL_STORAGE ignore"
            }
        }
        if (forceStop) cmds += "am force-stop $packageName"
        return cmds
    }

    /**
     * Runs [commands] as root. Individual `pm grant` failures (permission not declared by the app,
     * or not a runtime permission on this targetSdk) are expected and only logged.
     */
    suspend fun apply(
        packageName: String,
        requestedPermissions: Collection<String>,
        mode: Mode,
        forceStop: Boolean,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val cmds = commands(packageName, requestedPermissions, mode, forceStop)
        if (cmds.isEmpty()) return@withContext Result.success(Unit)
        if (!RootShell.isRoot()) return@withContext Result.failure(NoRootException())
        val result = RootShell.run(cmds)
        YLog.i("apply $mode to $packageName: ${cmds.size} cmd(s), success=${result.isSuccess}")
        result.err.forEach { YLog.w("  stderr: $it") }
        Result.success(Unit)
    }
}
