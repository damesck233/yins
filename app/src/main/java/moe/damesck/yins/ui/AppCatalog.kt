package moe.damesck.yins.ui

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.damesck.yins.data.PolicyContract

data class AppInfo(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
    /** Storage-related permissions declared in the manifest. */
    val storagePermissions: List<String>,
)

object AppCatalog {
    /** Installed apps that declare at least one storage/media permission. */
    suspend fun load(context: Context, includeSystem: Boolean): List<AppInfo> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
            .asSequence()
            .filter { it.packageName != PolicyContract.MANAGER_PACKAGE }
            .mapNotNull { pkg ->
                val app = pkg.applicationInfo ?: return@mapNotNull null
                val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                if (isSystem && !includeSystem) return@mapNotNull null
                val perms = pkg.requestedPermissions
                    ?.filter { PolicyContract.isStoragePermission(it) }
                    ?.takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null
                AppInfo(
                    packageName = pkg.packageName,
                    label = app.loadLabel(pm).toString(),
                    isSystem = isSystem,
                    storagePermissions = perms,
                )
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    fun label(context: Context, packageName: String): String = try {
        val pm = context.packageManager
        pm.getApplicationInfo(packageName, 0).loadLabel(pm).toString()
    } catch (t: Throwable) {
        packageName
    }

    fun declaredStoragePermissions(context: Context, packageName: String): List<String> = try {
        context.packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            ?.filter { PolicyContract.isStoragePermission(it) }
            ?: emptyList()
    } catch (t: Throwable) {
        emptyList()
    }
}
