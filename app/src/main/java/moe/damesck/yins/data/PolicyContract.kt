package moe.damesck.yins.data

import android.net.Uri

/**
 * Constants shared between the manager app and the hooked processes.
 * Keep this file free of Android framework dependencies beyond [Uri] so it is safe to load anywhere.
 */
object PolicyContract {
    const val MANAGER_PACKAGE = "moe.damesck.yins"

    const val AUTHORITY = "moe.damesck.yins.policy"
    // lazy: keeps this object usable in plain JVM unit tests where android.net.Uri is a stub
    val POLICIES_URI: Uri by lazy { Uri.parse("content://$AUTHORITY/policies") }

    const val COL_PACKAGE = "package_name"
    const val COL_USER_ID = "user_id"
    const val COL_MODE = "mode"
    const val COL_UPDATED_AT = "updated_at"

    /** Private methods handled by our hook inside MediaProvider.call(). */
    const val METHOD_PING = "yins.ping"
    const val METHOD_RELOAD = "yins.reload"
    const val METHOD_GRANT = "yins.grant"
    const val METHOD_CLEAR_GRANTS = "yins.clearGrants"

    /** Manager pushes the complete policy list; MediaProvider never has to read our provider. */
    const val METHOD_SET_POLICIES = "yins.setPolicies"

    /** Explicit broadcast from the MediaProvider hook asking the manager to push policies. */
    const val ACTION_REQUEST_POLICIES = "moe.damesck.yins.action.REQUEST_POLICIES"
    const val PUSH_RECEIVER = "moe.damesck.yins.PolicyPushReceiver"

    /** Explicit broadcast from the MediaProvider hook: a PARTIAL app is reading photos right now. */
    const val ACTION_MEDIA_ACCESS = "moe.damesck.yins.action.MEDIA_ACCESS"
    const val MEDIA_ACCESS_RECEIVER = "moe.damesck.yins.MediaAccessReceiver"
    const val EXTRA_UID = "yins.uid"

    /**
     * Methods on the manager's own provider ([AUTHORITY]) that MediaProvider's hook calls.
     * A provider call starts the manager process immediately; broadcasts to a cached app are
     * deferred by the system for a long time, so they are only a fallback.
     */
    const val PROVIDER_METHOD_MEDIA_ACCESS = "yins.mediaAccess"
    const val PROVIDER_METHOD_REQUEST_POLICIES = "yins.requestPolicies"

    const val EXTRA_PACKAGE = "yins.package"
    const val EXTRA_USER_ID = "yins.userId"
    const val EXTRA_URIS = "yins.uris"
    const val EXTRA_OK = "yins.ok"
    const val EXTRA_LOADED = "yins.loaded"
    const val EXTRA_HOOK_VERSION = "yins.hookVersion"
    const val EXTRA_POLICY_COUNT = "yins.policyCount"
    const val EXTRA_MODE = "yins.mode"

    /** String[] of "package|userId|MODE" entries. */
    const val EXTRA_POLICIES = "yins.policies"

    const val PERM_READ_EXTERNAL_STORAGE = "android.permission.READ_EXTERNAL_STORAGE"
    const val PERM_WRITE_EXTERNAL_STORAGE = "android.permission.WRITE_EXTERNAL_STORAGE"
    const val PERM_READ_MEDIA_IMAGES = "android.permission.READ_MEDIA_IMAGES"
    const val PERM_READ_MEDIA_VIDEO = "android.permission.READ_MEDIA_VIDEO"
    const val PERM_READ_MEDIA_AUDIO = "android.permission.READ_MEDIA_AUDIO"
    const val PERM_READ_MEDIA_VISUAL_USER_SELECTED = "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"
    const val PERM_ACCESS_MEDIA_LOCATION = "android.permission.ACCESS_MEDIA_LOCATION"
    const val PERM_MANAGE_EXTERNAL_STORAGE = "android.permission.MANAGE_EXTERNAL_STORAGE"

    /** Runtime (dangerous) permissions this module intercepts. */
    val RUNTIME_STORAGE_PERMISSIONS: Set<String> = setOf(
        PERM_READ_EXTERNAL_STORAGE,
        PERM_WRITE_EXTERNAL_STORAGE,
        PERM_READ_MEDIA_IMAGES,
        PERM_READ_MEDIA_VIDEO,
        PERM_READ_MEDIA_AUDIO,
        PERM_READ_MEDIA_VISUAL_USER_SELECTED,
        PERM_ACCESS_MEDIA_LOCATION,
    )

    /** Everything the module cares about, including the MANAGE_EXTERNAL_STORAGE app-op. */
    val ALL_STORAGE_PERMISSIONS: Set<String> = RUNTIME_STORAGE_PERMISSIONS + PERM_MANAGE_EXTERNAL_STORAGE

    fun isStoragePermission(name: String): Boolean = name in ALL_STORAGE_PERMISSIONS
}
