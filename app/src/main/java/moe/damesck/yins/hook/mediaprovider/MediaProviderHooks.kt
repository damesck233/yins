package moe.damesck.yins.hook.mediaprovider

import android.content.ContentProvider
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import moe.damesck.yins.YLog
import moe.damesck.yins.data.Mode
import moe.damesck.yins.data.PolicyContract
import moe.damesck.yins.data.PolicySnapshot

/**
 * Hooks inside the MediaProvider process (com.google.android.providers.media.module).
 *
 * Mechanism: the OS has *really* granted the app its storage permissions, so every permission
 * check the app performs says "granted". Here we make MediaProvider believe the app holds none of
 * them (BLANK) or only READ_MEDIA_VISUAL_USER_SELECTED (PARTIAL). MediaProvider's own, well-tested
 * scoped-storage logic then restricts the app to files it owns (+ media_grants for PARTIAL), for
 * both ContentResolver queries and raw file access through FUSE.
 */
object MediaProviderHooks {
    /**
     * Bump whenever code that runs inside MediaProvider changes. The manager compares it with the
     * value reported by the running hook to decide whether a reboot is needed; using the app's
     * versionCode would nag after UI-only updates.
     */
    const val HOOK_REVISION = 17

    private const val MEDIA_PROVIDER = "com.android.providers.media.MediaProvider"
    private const val LOCAL_CALLING_IDENTITY = "com.android.providers.media.LocalCallingIdentity"

    /** Fallback values (android16-release); the real ones are read reflectively at install time. */
    private val MASKED_PERMISSION_FIELDS = mapOf(
        "PERMISSION_IS_MANAGER" to (1 shl 2),
        "PERMISSION_IS_LEGACY_GRANTED" to (1 shl 9),
        "PERMISSION_IS_LEGACY_READ" to (1 shl 10),
        "PERMISSION_IS_LEGACY_WRITE" to (1 shl 11),
        "PERMISSION_READ_AUDIO" to (1 shl 16),
        "PERMISSION_READ_VIDEO" to (1 shl 17),
        "PERMISSION_READ_IMAGES" to (1 shl 18),
        "PERMISSION_WRITE_AUDIO" to (1 shl 19),
        "PERMISSION_WRITE_VIDEO" to (1 shl 20),
        "PERMISSION_WRITE_IMAGES" to (1 shl 21),
        "PERMISSION_IS_SYSTEM_GALLERY" to (1 shl 22),
        "PERMISSION_WRITE_EXTERNAL_STORAGE" to (1 shl 24),
        "PERMISSION_READ_MEDIA_VISUAL_USER_SELECTED" to (1 shl 27),
    )
    private const val USER_SELECTED_FIELD = "PERMISSION_READ_MEDIA_VISUAL_USER_SELECTED"

    private var maskedBits = 0
    private var userSelectedBit = 0

    /** READ_IMAGES | READ_VIDEO: a masked check on these means "the app is reading the library". */
    private var visualReadBits = 0

    @Volatile
    private var managerUid = -1

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cl = lpparam.classLoader
        val mpClass = XposedHelpers.findClass(MEDIA_PROVIDER, cl)
        val lciClass = XposedHelpers.findClass(LOCAL_CALLING_IDENTITY, cl)

        resolvePermissionBits(lciClass)

        // Each hook is independent: one failing must not stop the others (that would silently
        // leave the module half-installed, e.g. control channel up but masking off).
        step("onCreate") {
            XposedHelpers.findAndHookMethod(mpClass, "onCreate", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val ctx = (param.thisObject as ContentProvider).context ?: return
                    managerUid = try {
                        ctx.packageManager.getPackageUid(PolicyContract.MANAGER_PACKAGE, 0)
                    } catch (t: Throwable) {
                        -1
                    }
                    PolicyCache.start(ctx)
                    YLog.i("MediaProvider hooked, managerUid=$managerUid")
                }
            })
        }

        // Control channel used by the manager app.
        step("call") {
            XposedHelpers.findAndHookMethod(
                mpClass, "call", String::class.java, String::class.java, Bundle::class.java, CallChannelHook,
            )
        }

        // Single choke point for "what does this caller hold". hookAllMethods matches every
        // hasPermission overload by name, so a signature change in a MediaProvider update cannot
        // silently disable masking.
        step("hasPermission") {
            val n = XposedBridge.hookAllMethods(lciClass, "hasPermission", IdentityHook).size
            YLog.i("hooked $n hasPermission overload(s)")
        }

        // Creation paths keep the app's real bypass so an "all files" app can still create its
        // own directories/files anywhere; it just cannot see anybody else's.
        step("insertFileIfNecessaryForFuse") {
            XposedBridge.hookAllMethods(mpClass, "insertFileIfNecessaryForFuse", RelaxHook { _ -> true })
        }
        step("isDirAccessAllowedForFuse") {
            XposedBridge.hookAllMethods(mpClass, "isDirAccessAllowedForFuse", RelaxHook { args ->
                // accessType: 1 = READ, 2 = WRITE, 3 = CREATE, 4 = DELETE.
                // Only creation-style access is relaxed; listing stays filtered and deleting other
                // apps' (empty) directories stays forbidden.
                val accessType = args.getOrNull(2) as? Int ?: return@RelaxHook false
                accessType == 2 || accessType == 3
            })
        }
    }

    private inline fun step(name: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            YLog.e("hook step '$name' failed", t)
        }
    }

    private fun resolvePermissionBits(lciClass: Class<*>) {
        var bits = 0
        for ((name, fallback) in MASKED_PERMISSION_FIELDS) {
            val value = try {
                XposedHelpers.getStaticIntField(lciClass, name)
            } catch (t: Throwable) {
                YLog.w("LocalCallingIdentity.$name missing, using fallback $fallback")
                fallback
            }
            bits = bits or value
            if (name == USER_SELECTED_FIELD) userSelectedBit = value
            if (name == "PERMISSION_READ_IMAGES" || name == "PERMISSION_READ_VIDEO") visualReadBits = visualReadBits or value
        }
        maskedBits = bits
        YLog.i("masked permission bits = 0x${bits.toString(16)}")
    }

    /** Per-thread "do not mask" flag, raised while evaluating creation-time bypass checks. */
    object RelaxScope {
        private val depth = object : ThreadLocal<Int>() {
            override fun initialValue() = 0
        }

        fun enter() {
            depth.set(depth.get()!! + 1)
        }

        fun exit() {
            depth.set((depth.get()!! - 1).coerceAtLeast(0))
        }

        fun isRelaxed(): Boolean = depth.get()!! > 0
    }

    /** Raises [RelaxScope] around a ForFuse method when the uid (args[1]) is managed. */
    private class RelaxHook(private val applies: (Array<Any?>) -> Boolean) : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val uid = param.args.getOrNull(1) as? Int ?: return
            val mode = PolicyCache.modeForUid(uid) ?: return
            if ((mode == Mode.BLANK || mode == Mode.PARTIAL) && applies(param.args)) {
                RelaxScope.enter()
                param.setObjectExtra("yins.relaxed", true)
            }
        }

        override fun afterHookedMethod(param: MethodHookParam) {
            if (param.getObjectExtra("yins.relaxed") == true) RelaxScope.exit()
        }
    }

    private object IdentityHook : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            val permission = param.args.getOrNull(0) as? Int ?: return
            if (permission and maskedBits == 0) return
            if (RelaxScope.isRelaxed()) return
            val uid = try {
                XposedHelpers.getIntField(param.thisObject, "uid")
            } catch (t: Throwable) {
                return
            }
            when (val mode = PolicyCache.modeForUid(uid)) {
                Mode.BLANK -> {
                    param.result = false
                    if (permission and visualReadBits != 0) {
                        AccessLog.record(PolicyCache.appContext, uid, PolicyCache.packageForUid(uid), mode.name, blocked = true)
                    }
                }
                Mode.PARTIAL -> {
                    param.result = permission == userSelectedBit
                    if (permission and visualReadBits != 0) {
                        val ctx = PolicyCache.appContext
                        val pkg = PolicyCache.packageForUid(uid)
                        AccessNotifier.report(ctx, uid, pkg)
                        AccessLog.record(ctx, uid, pkg, mode.name, blocked = false)
                    }
                }
                else -> Unit
            }
        }
    }

    /**
     * media_grants only accepts picker URIs whose provider segment equals the *current* local
     * picker authority. OEMs swap it (ColorOS uses com.coloros.gallery3d.photopicker), so rebuild
     * every URI here from its file id using the authority MediaProvider actually has configured.
     */
    private fun normalizePickerUri(provider: Any, uri: Uri, userId: Int): Uri {
        val id = try {
            android.content.ContentUris.parseId(uri)
        } catch (t: Throwable) {
            return uri
        }
        val authority = localPickerAuthority(provider) ?: return uri
        return Uri.parse("content://media/picker/$userId/$authority/media/$id")
    }

    private fun localPickerAuthority(provider: Any): String? = try {
        val cls = XposedHelpers.findClass(
            "com.android.providers.media.photopicker.PickerSyncController",
            provider.javaClass.classLoader,
        )
        val instance = XposedHelpers.callStaticMethod(cls, "getInstanceOrThrow")
        XposedHelpers.callMethod(instance, "getLocalProvider") as? String
    } catch (t: Throwable) {
        YLog.w("cannot read local picker authority: $t")
        null
    }

    private object CallChannelHook : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val method = param.args[0] as? String ?: return
            if (!method.startsWith("yins.")) return
            val reply = Bundle()
            param.result = reply
            val caller = Binder.getCallingUid()
            val extras = param.args[2] as? Bundle ?: Bundle()
            // Diagnostics (ping) may come from adb shell / root; everything else only from the manager.
            val debugCaller = caller == Process.SHELL_UID || caller == Process.ROOT_UID
            val allowed = (managerUid > 0 && caller == managerUid) || (debugCaller && method == PolicyContract.METHOD_PING)
            if (!allowed) {
                YLog.w("rejected $method from uid $caller")
                reply.putBoolean(PolicyContract.EXTRA_OK, false)
                return
            }
            try {
                reply.putBoolean(PolicyContract.EXTRA_OK, handle(param.thisObject, method, extras, reply))
            } catch (t: Throwable) {
                YLog.e("call $method failed", t)
                reply.putBoolean(PolicyContract.EXTRA_OK, false)
            }
        }

        private fun handle(provider: Any, method: String, extras: Bundle, reply: Bundle): Boolean = when (method) {
            PolicyContract.METHOD_PING -> {
                reply.putInt(PolicyContract.EXTRA_HOOK_VERSION, HOOK_REVISION)
                reply.putBoolean(PolicyContract.EXTRA_LOADED, PolicyCache.loaded)
                reply.putInt(PolicyContract.EXTRA_POLICY_COUNT, PolicyCache.policyCount)
                extras.getString(PolicyContract.EXTRA_PACKAGE)?.let { pkg ->
                    val userId = extras.getInt(PolicyContract.EXTRA_USER_ID, 0)
                    reply.putString(PolicyContract.EXTRA_MODE, PolicyCache.modeForPackage(pkg, userId)?.name ?: "none")
                }
                true
            }
            PolicyContract.METHOD_GET_LOG -> {
                reply.putStringArray(PolicyContract.EXTRA_LOG, AccessLog.encodeAll())
                true
            }
            PolicyContract.METHOD_CLEAR_LOG -> {
                AccessLog.clear()
                true
            }
            PolicyContract.METHOD_RELOAD -> PolicyCache.reload()
            PolicyContract.METHOD_SET_POLICIES -> {
                val entries = extras.getStringArray(PolicyContract.EXTRA_POLICIES) ?: return false
                PolicyCache.set(PolicySnapshot.decode(entries), "push")
                true
            }
            PolicyContract.METHOD_GRANT -> {
                val pkg = extras.getString(PolicyContract.EXTRA_PACKAGE) ?: return false
                val userId = extras.getInt(PolicyContract.EXTRA_USER_ID, 0)
                @Suppress("DEPRECATION")
                val uris: ArrayList<Uri> = extras.getParcelableArrayList(PolicyContract.EXTRA_URIS) ?: return false
                val normalized = ArrayList(uris.map { normalizePickerUri(provider, it, userId) })
                val grants = XposedHelpers.getObjectField(provider, "mMediaGrants")
                XposedHelpers.callMethod(grants, "addMediaGrantsForPackage", pkg, normalized, userId)
                YLog.i("granted ${normalized.size} item(s) to $pkg")
                true
            }
            PolicyContract.METHOD_CLEAR_GRANTS -> {
                val pkg = extras.getString(PolicyContract.EXTRA_PACKAGE) ?: return false
                val userId = extras.getInt(PolicyContract.EXTRA_USER_ID, 0)
                val grants = XposedHelpers.getObjectField(provider, "mMediaGrants")
                XposedHelpers.callMethod(
                    grants, "removeAllMediaGrantsForPackages", arrayOf(pkg), "yins", Integer.valueOf(userId),
                )
                true
            }
            else -> false
        }
    }
}
