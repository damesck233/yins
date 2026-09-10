package moe.damesck.yins.data

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import moe.damesck.yins.YLog
import moe.damesck.yins.notify.AccessNotifications
import kotlin.concurrent.thread

/**
 * Read-only ContentProvider that exposes all policies to the hooked processes
 * (MediaProvider, PermissionController, system_server), plus a small call() surface that
 * MediaProvider's hook uses to reach the manager *immediately* (broadcasts would be deferred).
 *
 * Declared directBootAware so it is reachable before the user unlocks the device.
 */
class PolicyProvider : ContentProvider() {
    @Volatile
    private var mediaProviderUids: Set<Int>? = null

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val ctx = context ?: return emptyCursor()
        val cursor = MatrixCursor(COLUMNS)
        val policies = PolicyDatabase.get(ctx).policyDao().getAllBlocking()
        for (p in policies) {
            cursor.addRow(arrayOf<Any>(p.packageName, p.userId, p.mode.name, if (p.hideDirectories) 1 else 0, p.updatedAt))
        }
        return cursor
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val ctx = context ?: return null
        val reply = Bundle()
        if (!isMediaProviderCaller(Binder.getCallingUid())) {
            YLog.w("provider call $method rejected from uid ${Binder.getCallingUid()}")
            reply.putBoolean(PolicyContract.EXTRA_OK, false)
            return reply
        }
        when (method) {
            PolicyContract.PROVIDER_METHOD_MEDIA_ACCESS -> {
                val pkg = extras?.getString(PolicyContract.EXTRA_PACKAGE)
                if (pkg != null) {
                    // Off the binder thread: building the notification touches PackageManager.
                    thread(name = "yins-media-access") { AccessNotifications.showReading(ctx, pkg) }
                }
                reply.putBoolean(PolicyContract.EXTRA_OK, pkg != null)
            }
            PolicyContract.PROVIDER_METHOD_REQUEST_POLICIES -> {
                // The caller is MediaProvider waiting on this binder call; push from another thread.
                thread(name = "yins-push") { MediaProviderClient.pushAll(ctx) }
                reply.putBoolean(PolicyContract.EXTRA_OK, true)
            }
            else -> reply.putBoolean(PolicyContract.EXTRA_OK, false)
        }
        return reply
    }

    private fun isMediaProviderCaller(uid: Int): Boolean {
        if (uid == Process.SYSTEM_UID || uid == Process.ROOT_UID || uid == Process.myUid()) return true
        val known = mediaProviderUids ?: resolveMediaProviderUids().also { mediaProviderUids = it }
        return uid in known
    }

    private fun resolveMediaProviderUids(): Set<Int> {
        val pm = context?.packageManager ?: return emptySet()
        return MEDIA_PROVIDER_PACKAGES.mapNotNull { pkg ->
            try {
                pm.getPackageUid(pkg, 0)
            } catch (t: Throwable) {
                null
            }
        }.toSet()
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.dir/vnd.$AUTHORITY.policy"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("read-only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("read-only")

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("read-only")

    private fun emptyCursor() = MatrixCursor(COLUMNS)

    companion object {
        private const val AUTHORITY = PolicyContract.AUTHORITY
        private val MEDIA_PROVIDER_PACKAGES = listOf(
            "com.android.providers.media.module",
            "com.google.android.providers.media.module",
        )
        val COLUMNS = arrayOf(
            PolicyContract.COL_PACKAGE,
            PolicyContract.COL_USER_ID,
            PolicyContract.COL_MODE,
            PolicyContract.COL_HIDE_DIRS,
            PolicyContract.COL_UPDATED_AT,
        )
    }
}
